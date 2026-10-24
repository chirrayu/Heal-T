/*
 * Soldier Suit Firmware — ESP32 (real sensors)
 * ------------------------------------------------------------------
 * Sensors:
 *   MAX30100  -> heart rate + SpO2                (I2C, addr 0x57)
 *   MPU-6050  -> accelerometer (activity + blast/impact detection) (I2C, addr 0x68)
 *   DS18B20   -> body/ambient temperature           (OneWire, GPIO4)
 *   NEO-6M    -> GPS location                       (UART2, RX=16 TX=17)
 *
 * Connectivity (same dual-mode design as before):
 *   MODE_WIFI -> HTTP POST to backend REST endpoints (/vitals/, /map/location)
 *   MODE_BLE  -> BLE GATT peripheral, phone app connects directly
 *
 * Local storage: every reading cycle is also appended as one JSON line
 * to /data_log.jsonl on LittleFS (flash filesystem) — a persistent
 * local record independent of whether the network send succeeds.
 *
 * Mode + WiFi credentials persist in NVS (Preferences), applied via
 * clean reboot. BLE mode always clears stored WiFi credentials
 * (button toggle or auto-fallback after WIFI_MAX_RETRIES failures).
 *
 * Backend config (host/port/soldier id): the phone app can now send
 * these along with the WiFi credentials over the same BLE_WIFI_CONFIG
 * characteristic when switching the suit into WiFi mode. They persist
 * in NVS just like the WiFi credentials, and the #define values below
 * are used as first-boot defaults only (and as a fallback if the app
 * never sent them).
 *
 * Libraries needed (Arduino IDE Library Manager):
 *   - ArduinoJson        (Benoit Blanchon)
 *   - MAX30100lib        (OXullo Intersecans)
 *   - Adafruit MPU6050   (+ Adafruit Unified Sensor, Adafruit BusIO — auto-pulled as deps)
 *   - OneWire            (Jim Studt / Paul Stoffregen)
 *   - DallasTemperature  (Miles Burton)
 *   - TinyGPSPlus        (Mikal Hart)
 *   - LittleFS + BLE are bundled with the ESP32 board package, no install needed
 *
 * Known limitation: connectWiFi() blocks for up to WIFI_CONNECT_TIMEOUT_MS
 * during (re)connect attempts, during which pox.update() isn't called —
 * MAX30100 heart-rate accuracy may dip briefly right after a WiFi drop.
 * A fully non-blocking WiFi state machine would fix this but is a
 * larger rewrite; flagging it rather than hiding it.
 * ------------------------------------------------------------------
 */

#include <WiFi.h>
#include <HTTPClient.h>
#include <WiFiClientSecure.h>
#include <Preferences.h>
#include <ArduinoJson.h>
#include <LittleFS.h>
#include <Wire.h>

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

#include "MAX30100_PulseOximeter.h"
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include <OneWire.h>
#include <DallasTemperature.h>
#include <TinyGPSPlus.h>

// The ESP32 certificate bundle validates normal public HTTPS certificates
// (including the certificates used by managed hosts such as Render).  Do not
// replace this with setInsecure(): health and location data must be protected
// in transit.
#if __has_include(<esp_crt_bundle.h>)
#include <esp_crt_bundle.h>
#define HAS_CERT_BUNDLE 1
#else
#define HAS_CERT_BUNDLE 0
#endif

// ── User config ─────────────────────────────────────────────────
#define WIFI_SSID       "YOUR_WIFI_SSID"       // first-boot fallback only
#define WIFI_PASSWORD   "YOUR_WIFI_PASSWORD"
#define BACKEND_HOST    "15.206.194.22"          // first-boot fallback only — app can override over BLE
#define BACKEND_PORT    8000                    // first-boot fallback only — app can override over BLE
#define BACKEND_SCHEME  "http"                  // use "https" for a public deployment
#define SOLDIER_ID      "SOLDIER-001"           // first-boot fallback only — app can override over BLE; must match an existing SoldierModel.id in the DB

#define POST_INTERVAL_MS       5000
#define WIFI_CONNECT_TIMEOUT_MS 10000
#define WIFI_MAX_RETRIES       3

#define BUTTON_PIN      0
#define BUTTON_HOLD_MS  1000

// BLE UUIDs — must match the Android app's BLE receiver
#define BLE_SERVICE_UUID          "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
#define BLE_VITALS_CHAR_UUID      "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
#define BLE_LOCATION_CHAR_UUID    "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
#define BLE_WIFI_CONFIG_CHAR_UUID "6e400004-b5a3-f393-e0a9-e50e24dcca9e"

// Sensor wiring
#define ONE_WIRE_BUS       4     // DS18B20 data pin
#define GPS_RX_PIN         16    // ESP32 RX2 <- GPS TX
#define GPS_TX_PIN         17    // ESP32 TX2 -> GPS RX
#define GPS_BAUD           9600

#define SHOCK_THRESHOLD_G  3.0f  // accel magnitude (in g) considered a blast/impact event start

#define LOG_FILE_PATH        "/data_log.jsonl"
#define MAX_LOG_FILE_BYTES    (200 * 1024)   // simple cap; file is cleared if exceeded

// ── Mode ────────────────────────────────────────────────────────
enum SuitMode { MODE_WIFI = 0, MODE_BLE = 1 };
Preferences prefs;
SuitMode currentMode;

// ── Backend config (host/port/soldier id) loaded from NVS at boot,
// with the #defines above as first-boot defaults ───────────────
String currentBackendHost;
int currentBackendPort;
String currentBackendScheme;
String currentSoldierId;

// ── Forward declarations ───────────────────────────────────────
// Plain .cpp files (PlatformIO) need these; Arduino IDE's .ino format
// auto-generates them invisibly, which is why the original file didn't
// need this section.
void saveModeAndReboot(SuitMode m);
void updateAccelMonitor();
bool connectWiFi();
void appendLog(const String &vitalsJson, const String &locationJson);
void rotateLogIfNeeded();
String buildVitalsJson();
String buildLocationJson();
bool consumeShockEvent(float &peakAccelG, float &durationMs);
void loadBackendConfig();
void saveBackendConfig(const String &host, int port, const String &scheme, const String &soldierId);

// ── BLE globals ─────────────────────────────────────────────────
BLEServer* bleServer = nullptr;
BLECharacteristic* vitalsChar = nullptr;
BLECharacteristic* locationChar = nullptr;
BLECharacteristic* wifiConfigChar = nullptr;
bool bleDeviceConnected = false;

volatile bool pendingWifiSwitch = false;
String pendingSsid;
String pendingPassword;
// Backend config fields the app can optionally send alongside ssid/password.
// Left empty means "app didn't send this field, keep whatever's already stored".
String pendingBackendHost;
int pendingBackendPort = 0;
String pendingBackendScheme;
String pendingSoldierId;

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* server) override {
    bleDeviceConnected = true;
    Serial.println("[BLE] Phone connected");
  }
  void onDisconnect(BLEServer* server) override {
    bleDeviceConnected = false;
    Serial.println("[BLE] Phone disconnected, restarting advertising");
    delay(100);
    BLEDevice::startAdvertising();
  }
};

class WifiConfigCallback : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* characteristic) override {
    std::string value = characteristic->getValue();
    if (value.length() == 0) return;

    Serial.printf("[BLE] WiFi config write received (%d bytes)\n", value.length());

    StaticJsonDocument<384> doc;
    DeserializationError err = deserializeJson(doc, value);
    if (err) {
      Serial.printf("[BLE] WiFi config JSON parse failed: %s\n", err.c_str());
      return;
    }

    const char* ssid = doc["ssid"];
    const char* password = doc["password"];
    if (!ssid || strlen(ssid) == 0) {
      Serial.println("[BLE] WiFi config missing ssid, ignoring");
      return;
    }

    pendingSsid = String(ssid);
    pendingPassword = password ? String(password) : String("");

    // Optional backend config fields — app fills these in from its own
    // "connect suit to WiFi" screen (backend host/IP, port, soldier id).
    // Any field left out of the JSON keeps its previously stored value.
    const char* backendHost = doc["backend_host"];
    pendingBackendHost = backendHost ? String(backendHost) : String("");

    pendingBackendPort = doc["backend_port"] | 0;

    const char* backendScheme = doc["backend_scheme"];
    pendingBackendScheme = backendScheme ? String(backendScheme) : String("");

    const char* soldierId = doc["suit_id"] ? doc["suit_id"].as<const char*>() : (doc["soldier_id"] ? doc["soldier_id"].as<const char*>() : nullptr);
    pendingSoldierId = soldierId ? String(soldierId) : String("");

    pendingWifiSwitch = true;
  }
};

// ── Timing ──────────────────────────────────────────────────────
unsigned long lastPostTime = 0;
unsigned long buttonPressStart = 0;
bool buttonHeld = false;
int wifiFailCount = 0;

// ── Sensor objects ──────────────────────────────────────────────
PulseOximeter pox;
bool poxReady = false;

Adafruit_MPU6050 mpu;
bool mpuReady = false;

OneWire oneWire(ONE_WIRE_BUS);
DallasTemperature ds18b20(&oneWire);

HardwareSerial GPSSerial(2);
TinyGPSPlus gps;

// Blast/impact detection state
bool inShockEvent = false;
unsigned long shockStartMs = 0;
float shockPeakG = 0;
float lastPeakAccelG = 0;
float lastDurationMs = 0;
bool shockEventReady = false;

// Rough activity index: exponential moving average of accel deviation from 1g, scaled 0-100
float activityEma = 0;

// ==================================================================
// Sensor setup
// ==================================================================
void setupSensors() {
  Wire.begin(); // default SDA=21, SCL=22 on most ESP32 dev boards

  Serial.println("[Sensors] Initializing MAX30100...");
  if (pox.begin()) {
    poxReady = true;
    pox.setIRLedCurrent(MAX30100_LED_CURR_7_6MA);
    Serial.println("[Sensors] MAX30100 ready");
  } else {
    Serial.println("[Sensors] MAX30100 FAILED to initialize - check wiring");
  }

  Serial.println("[Sensors] Initializing MPU-6050...");
  if (mpu.begin()) {
    mpuReady = true;
    mpu.setAccelerometerRange(MPU6050_RANGE_8_G);
    mpu.setGyroRange(MPU6050_RANGE_500_DEG);
    mpu.setFilterBandwidth(MPU6050_BAND_21_HZ);
    Serial.println("[Sensors] MPU-6050 ready");
  } else {
    Serial.println("[Sensors] MPU-6050 FAILED to initialize - check wiring");
  }

  ds18b20.begin();
  Serial.printf("[Sensors] DS18B20 devices found: %d\n", ds18b20.getDeviceCount());

  GPSSerial.begin(GPS_BAUD, SERIAL_8N1, GPS_RX_PIN, GPS_TX_PIN);
  Serial.println("[Sensors] GPS serial started");
}

// Call every loop iteration, unconditionally, as cheaply as possible.
// MAX30100 needs frequent update() calls to keep its beat-detection
// timing accurate; GPS needs its serial buffer drained continuously
// or NMEA sentences get corrupted/dropped.
void pumpSensors() {
  if (poxReady) pox.update();

  while (GPSSerial.available() > 0) {
    gps.encode(GPSSerial.read());
  }

  updateAccelMonitor();
}

// ==================================================================
// MPU-6050: activity index + blast/impact (shock) event detection
// ==================================================================
void updateAccelMonitor() {
  if (!mpuReady) return;

  sensors_event_t a, g, tempEvent;
  mpu.getEvent(&a, &g, &tempEvent);

  // acceleration.{x,y,z} are in m/s^2 from Adafruit_MPU6050; convert to g
  float magnitudeG = sqrt(
    a.acceleration.x * a.acceleration.x +
    a.acceleration.y * a.acceleration.y +
    a.acceleration.z * a.acceleration.z
  ) / 9.80665f;

  // Rolling activity index from steady-state motion (not the shock spike itself)
  activityEma = 0.9f * activityEma + 0.1f * fabs(magnitudeG - 1.0f);

  if (!inShockEvent) {
    if (magnitudeG > SHOCK_THRESHOLD_G) {
      inShockEvent = true;
      shockStartMs = millis();
      shockPeakG = magnitudeG;
    }
  } else {
    if (magnitudeG > shockPeakG) shockPeakG = magnitudeG;

    if (magnitudeG <= SHOCK_THRESHOLD_G) {
      lastDurationMs = (float)(millis() - shockStartMs);
      lastPeakAccelG = shockPeakG;
      shockEventReady = true;
      inShockEvent = false;
    }
  }
}

// ==================================================================
// Sensor reads — return sentinel values when no reading is available;
// buildVitalsJson()/buildLocationJson() below translate those to JSON
// null so pydantic's Optional fields stay null rather than getting a
// misleading zero.
// ==================================================================
int readHR() {
  if (!poxReady) return -1;
  float hr = pox.getHeartRate();
  return (hr > 0) ? (int)hr : -1;
}

int readSpO2() {
  if (!poxReady) return -1;
  uint8_t spo2 = pox.getSpO2();
  return (spo2 > 0) ? (int)spo2 : -1;
}

float readTemp() {
  ds18b20.requestTemperatures();
  float tempF = ds18b20.getTempFByIndex(0);
  if (tempF == DEVICE_DISCONNECTED_F) return NAN;
  return tempF;
}

int readBattery() {
  // TODO: no battery fuel-gauge/ADC wiring specified yet. If you add a
  // voltage divider into an ADC pin, read it here and convert to %.
  return 100;
}

int readActivityIndex() {
  if (!mpuReady) return 0;
  int index = (int)(activityEma * 100.0f);
  if (index < 0) index = 0;
  if (index > 100) index = 100;
  return index;
}

int readRespiratoryRate() {
  // TODO: no dedicated respiratory sensor in this hardware list. Could
  // be estimated from chest-mounted MPU6050 breathing motion with a
  // bandpass filter, but that's a separate signal-processing project.
  return -1;
}

bool consumeShockEvent(float &peakAccelG, float &durationMs) {
  if (!shockEventReady) return false;
  peakAccelG = lastPeakAccelG;
  durationMs = lastDurationMs;
  shockEventReady = false;
  return true;
}

bool readGPS(double &lat, double &lng) {
  if (gps.location.isValid() && gps.location.age() < 2000) {
    lat = gps.location.lat();
    lng = gps.location.lng();
    return true;
  }
  return false;
}

String getIsoTimestamp() {
  // Placeholder — wire up NTP (configTime) or an RTC module for real timestamps.
  return "";
}

// ==================================================================
// JSON builders — match VitalsIn / LocationIn schemas exactly
// ==================================================================
String buildVitalsJson() {
  StaticJsonDocument<384> doc;
  doc["soldier_id"] = currentSoldierId;

  int hr = readHR();
  if (hr >= 0) doc["hr"] = hr; else doc["hr"] = nullptr;

  int spo2 = readSpO2();
  if (spo2 >= 0) doc["spo2"] = spo2; else doc["spo2"] = nullptr;

  float temp = readTemp();
  if (isnan(temp)) doc["temp"] = nullptr; else doc["temp"] = temp;

  doc["battery"] = readBattery();
  doc["activity_index"] = readActivityIndex();

  int rr = readRespiratoryRate();
  if (rr >= 0) doc["respiratory_rate"] = rr; else doc["respiratory_rate"] = nullptr;

  float peakAccel, durationMs;
  if (consumeShockEvent(peakAccel, durationMs)) {
    doc["peak_accel_g"] = peakAccel;
    doc["duration_ms"] = durationMs;
    String ts = getIsoTimestamp();
    if (ts.length() > 0) doc["blast_timestamp"] = ts;
  }

  String out;
  serializeJson(doc, out);
  return out;
}

String buildLocationJson() {
  StaticJsonDocument<192> doc;
  doc["soldier_id"] = currentSoldierId;
  double lat, lng;
  if (readGPS(lat, lng)) {
    doc["latitude"] = lat;
    doc["longitude"] = lng;
  } else {
    return ""; // no fix, skip this cycle
  }
  String out;
  serializeJson(doc, out);
  return out;
}

// ==================================================================
// Local JSON logging (LittleFS)
// ==================================================================
void setupLocalStorage() {
  if (!LittleFS.begin(true)) {
    Serial.println("[Log] LittleFS mount failed even after format attempt");
    return;
  }
  Serial.println("[Log] LittleFS mounted");
}

void rotateLogIfNeeded() {
  File f = LittleFS.open(LOG_FILE_PATH, "r");
  if (!f) return;
  size_t size = f.size();
  f.close();

  if (size > MAX_LOG_FILE_BYTES) {
    // Simple cap: clear the file rather than trimming old entries.
    // Fine for a bounded local record; upgrade to a ring-buffer or
    // periodic offload to SD/backend if you need full history.
    Serial.println("[Log] Log file exceeded cap, clearing");
    LittleFS.remove(LOG_FILE_PATH);
  }
}

void appendLog(const String &vitalsJson, const String &locationJson) {
  DynamicJsonDocument record(768);
  record["recorded_at_ms"] = millis();

  DynamicJsonDocument vDoc(384);
  if (deserializeJson(vDoc, vitalsJson) == DeserializationError::Ok) {
    record["vitals"] = vDoc.as<JsonObject>();
  }

  if (locationJson.length() > 0) {
    DynamicJsonDocument lDoc(192);
    if (deserializeJson(lDoc, locationJson) == DeserializationError::Ok) {
      record["location"] = lDoc.as<JsonObject>();
    }
  }

  File f = LittleFS.open(LOG_FILE_PATH, "a");
  if (!f) {
    Serial.println("[Log] Failed to open log file for append");
    return;
  }
  serializeJson(record, f);
  f.println();
  f.close();

  rotateLogIfNeeded();
}

// ==================================================================
// WiFi mode
// ==================================================================
void loadWifiCredentials(String &ssid, String &password) {
  prefs.begin("suit", true);
  ssid = prefs.getString("wifi_ssid", WIFI_SSID);
  password = prefs.getString("wifi_pass", WIFI_PASSWORD);
  prefs.end();
}

void saveWifiCredentials(const String &ssid, const String &password) {
  prefs.begin("suit", false);
  prefs.putString("wifi_ssid", ssid);
  prefs.putString("wifi_pass", password);
  prefs.end();
}

void clearWifiCredentials() {
  prefs.begin("suit", false);
  prefs.remove("wifi_ssid");
  prefs.remove("wifi_pass");
  prefs.end();
  Serial.println("[WiFi] Stored credentials cleared");
}

// ── Backend config persistence (host/port/soldier id) ───────────
// Same NVS namespace as WiFi credentials/mode; loaded once at boot
// into currentBackendHost/currentBackendPort/currentSoldierId, and
// re-saved (and re-loaded on the following reboot) whenever the app
// sends new values over BLE_WIFI_CONFIG_CHAR_UUID.
void loadBackendConfig() {
  prefs.begin("suit", true);
  currentBackendHost = prefs.getString("backend_host", BACKEND_HOST);
  currentBackendPort = prefs.getInt("backend_port", BACKEND_PORT);
  currentBackendScheme = prefs.getString("backend_scheme", BACKEND_SCHEME);
  currentBackendScheme.toLowerCase();
  if (currentBackendScheme != "http" && currentBackendScheme != "https") {
    Serial.println("[Config] Invalid backend scheme; using HTTP");
    currentBackendScheme = "http";
  }
  currentSoldierId = prefs.getString("soldier_id", SOLDIER_ID);
  prefs.end();
  Serial.printf("[Config] Backend %s://%s:%d, soldier_id=%s\n",
                currentBackendScheme.c_str(), currentBackendHost.c_str(), currentBackendPort, currentSoldierId.c_str());
}

void saveBackendConfig(const String &host, int port, const String &scheme, const String &soldierId) {
  prefs.begin("suit", false);
  if (host.length() > 0) prefs.putString("backend_host", host);
  if (port > 0) prefs.putInt("backend_port", port);
  if (scheme == "http" || scheme == "https") prefs.putString("backend_scheme", scheme);
  if (soldierId.length() > 0) prefs.putString("soldier_id", soldierId);
  prefs.end();
}

bool connectWiFi() {
  String ssid, password;
  loadWifiCredentials(ssid, password);

  Serial.printf("[WiFi] Connecting to %s...\n", ssid.c_str());
  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid.c_str(), password.c_str());

  unsigned long start = millis();
  while (WiFi.status() != WL_CONNECTED) {
    if (millis() - start > WIFI_CONNECT_TIMEOUT_MS) {
      Serial.println("[WiFi] Connect timed out");
      return false;
    }
    delay(250);
    Serial.print(".");
  }
  Serial.printf("\n[WiFi] Connected, IP: %s\n", WiFi.localIP().toString().c_str());
  return true;
}

void postJson(const char* path, const String& body) {
  if (body.length() == 0) return;
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("[WiFi] Not connected, skipping POST");
    return;
  }

  HTTPClient http;
  String url = currentBackendScheme + "://" + currentBackendHost + ":" + String(currentBackendPort) + path;

  if (currentBackendScheme == "https") {
#if HAS_CERT_BUNDLE
    WiFiClientSecure secureClient;
    secureClient.setCACertBundle(rootca_crt_bundle_attach);
    if (!http.begin(secureClient, url)) {
      Serial.printf("[WiFi] Could not start HTTPS request to %s\n", url.c_str());
      return;
    }
#else
    Serial.println("[WiFi] HTTPS requires an ESP32 framework with the certificate bundle");
    return;
#endif
  } else {
    if (!http.begin(url)) {
      Serial.printf("[WiFi] Could not start HTTP request to %s\n", url.c_str());
      return;
    }
  }
  http.addHeader("Content-Type", "application/json");

  int code = http.POST(body);
  if (code > 0) {
    Serial.printf("[WiFi] POST %s -> %d\n", path, code);
  } else {
    Serial.printf("[WiFi] POST %s failed: %s\n", path, http.errorToString(code).c_str());
  }
  http.end();
}

void runWifiMode() {
  if (WiFi.status() != WL_CONNECTED) {
    if (!connectWiFi()) {
      wifiFailCount++;
      Serial.printf("[WiFi] Connect failed (%d/%d)\n", wifiFailCount, WIFI_MAX_RETRIES);

      if (wifiFailCount >= WIFI_MAX_RETRIES) {
        Serial.println("[WiFi] Max retries reached, falling back to BLE mode");
        saveModeAndReboot(MODE_BLE);
      }

      delay(2000);
      return;
    }
    wifiFailCount = 0;
  }

  if (millis() - lastPostTime >= POST_INTERVAL_MS) {
    lastPostTime = millis();
    String vJson = buildVitalsJson();
    String lJson = buildLocationJson();
    postJson("/vitals/", vJson);
    postJson("/map/location", lJson);
    appendLog(vJson, lJson);
  }
}

// ==================================================================
// BLE mode
// ==================================================================
void setupBLE() {
  BLEDevice::init((String("SuitBeacon-") + currentSoldierId).c_str());
  BLEDevice::setMTU(512);
  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new ServerCallbacks());

  BLEService* service = bleServer->createService(BLE_SERVICE_UUID);

  vitalsChar = service->createCharacteristic(
    BLE_VITALS_CHAR_UUID,
    BLECharacteristic::PROPERTY_NOTIFY | BLECharacteristic::PROPERTY_READ
  );
  vitalsChar->addDescriptor(new BLE2902());

  locationChar = service->createCharacteristic(
    BLE_LOCATION_CHAR_UUID,
    BLECharacteristic::PROPERTY_NOTIFY | BLECharacteristic::PROPERTY_READ
  );
  locationChar->addDescriptor(new BLE2902());

  wifiConfigChar = service->createCharacteristic(
    BLE_WIFI_CONFIG_CHAR_UUID,
    BLECharacteristic::PROPERTY_WRITE
  );
  wifiConfigChar->setCallbacks(new WifiConfigCallback());

  service->start();

  BLEAdvertising* advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(BLE_SERVICE_UUID);
  advertising->setScanResponse(true);
  advertising->setMinPreferred(0x06);
  advertising->setMinPreferred(0x12);
  BLEDevice::startAdvertising();

  Serial.println("[BLE] Advertising as SuitBeacon, waiting for phone...");
}

void runBleMode() {
  if (!bleDeviceConnected) return;

  if (millis() - lastPostTime >= POST_INTERVAL_MS) {
    lastPostTime = millis();

    String vJson = buildVitalsJson();
    vitalsChar->setValue(vJson.c_str());
    vitalsChar->notify();
    Serial.println("[BLE] Notified vitals: " + vJson);

    String lJson = buildLocationJson();
    if (lJson.length() > 0) {
      locationChar->setValue(lJson.c_str());
      locationChar->notify();
      Serial.println("[BLE] Notified location: " + lJson);
    }

    appendLog(vJson, lJson);
  }
}

// ==================================================================
// Mode persistence + button toggle
// ==================================================================
SuitMode loadMode() {
  prefs.begin("suit", true);
  SuitMode m = (SuitMode)prefs.getUChar("mode", MODE_WIFI);
  prefs.end();
  return m;
}

void saveModeAndReboot(SuitMode m) {
  if (m == MODE_BLE) {
    clearWifiCredentials();
  }

  prefs.begin("suit", false);
  prefs.putUChar("mode", (uint8_t)m);
  prefs.end();
  Serial.printf("[Mode] Switching to %s, rebooting...\n",
                m == MODE_WIFI ? "WIFI" : "BLE");
  delay(300);
  ESP.restart();
}

void checkButton() {
  bool pressed = (digitalRead(BUTTON_PIN) == LOW);

  if (pressed && !buttonHeld) {
    buttonHeld = true;
    buttonPressStart = millis();
  } else if (!pressed && buttonHeld) {
    buttonHeld = false;
  } else if (pressed && buttonHeld) {
    if (millis() - buttonPressStart >= BUTTON_HOLD_MS) {
      SuitMode next = (currentMode == MODE_WIFI) ? MODE_BLE : MODE_WIFI;
      saveModeAndReboot(next);
    }
  }
}

// ==================================================================
// Setup / loop
// ==================================================================
void setup() {
  Serial.begin(115200);
  delay(300);

  pinMode(BUTTON_PIN, INPUT_PULLUP);

  setupLocalStorage();
  setupSensors();
  loadBackendConfig();

  currentMode = loadMode();
  Serial.printf("[Boot] Starting in %s mode\n",
                currentMode == MODE_WIFI ? "WIFI" : "BLE");

  if (currentMode == MODE_WIFI) {
    connectWiFi();
  } else {
    setupBLE();
  }
}

void loop() {
  pumpSensors();
  checkButton();

  if (pendingWifiSwitch) {
    pendingWifiSwitch = false;
    Serial.printf("[BLE] Saving new WiFi credentials for '%s'\n", pendingSsid.c_str());
    saveWifiCredentials(pendingSsid, pendingPassword);
    saveBackendConfig(pendingBackendHost, pendingBackendPort, pendingBackendScheme, pendingSoldierId);
    saveModeAndReboot(MODE_WIFI);
  }

  if (currentMode == MODE_WIFI) {
    runWifiMode();
  } else {
    runBleMode();
  }
}
