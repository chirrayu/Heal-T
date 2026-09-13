package com.example.healthmonitor

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ApiService {

    // ── TLS Certificate Pinning ────────────────────────────────────
    // Configures certificate pinner to block Man-in-the-Middle (MitM) attacks.
    // Pin can be configured with SHA-256 SPKI fingerprint for production servers.
    private val certificatePinner = CertificatePinner.Builder().apply {
        // Example pin structure:
        // add(NetworkConfig.HOST, "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
    }.build()

    private val client = OkHttpClient.Builder()
        .certificatePinner(certificatePinner)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    // ── Auth token storage ────────────────────────────────────────
    private var authToken: String? = null

    fun init(context: Context) {
        if (authToken == null) {
            authToken = TokenStorage.getToken(context)
        }
    }

    fun setToken(token: String) {
        authToken = token
    }

    fun getToken(): String? = authToken

    fun isLoggedIn(): Boolean = !authToken.isNullOrBlank()

    fun logout(context: Context? = null) {
        authToken = null
        if (context != null) {
            TokenStorage.clear(context)
        }
    }

    // ── Request helpers ───────────────────────────────────────────
    private fun getRequest(path: String): Request {
        val builder = Request.Builder()
            .url("${NetworkConfig.BASE_URL}$path")
            .get()
        authToken?.let {
            builder.addHeader("Authorization", "Bearer $it")
        }
        return builder.build()
    }

    private fun postRequest(path: String, body: JSONObject): Request {
        val builder = Request.Builder()
            .url("${NetworkConfig.BASE_URL}$path")
            .post(body.toString().toRequestBody(JSON))
        authToken?.let {
            builder.addHeader("Authorization", "Bearer $it")
        }
        return builder.build()
    }

    private fun putRequest(path: String, body: JSONObject): Request {
        val builder = Request.Builder()
            .url("${NetworkConfig.BASE_URL}$path")
            .put(body.toString().toRequestBody(JSON))
        authToken?.let {
            builder.addHeader("Authorization", "Bearer $it")
        }
        return builder.build()
    }

    private fun deleteRequest(path: String): Request {
        val builder = Request.Builder()
            .url("${NetworkConfig.BASE_URL}$path")
            .delete()
        authToken?.let {
            builder.addHeader("Authorization", "Bearer $it")
        }
        return builder.build()
    }


    // ── Auth ──────────────────────────────────────────────────────
    suspend fun login(username: String, password: String, context: Context? = null): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val formBody = okhttp3.FormBody.Builder()
                    .add("username", username)
                    .add("password", password)
                    .build()

                val request = Request.Builder()
                    .url("${NetworkConfig.BASE_URL}/auth/login")
                    .post(formBody)
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: return@withContext false
                    val json = JSONObject(bodyString)
                    val token = json.getString("access_token")
                    val role = json.optString("role", "admin")
                    val opName = json.optString("name", username)

                    authToken = token

                    if (context != null) {
                        TokenStorage.saveToken(context, token)
                        TokenStorage.saveUserRole(context, role)
                        TokenStorage.saveOperatorName(context, opName)
                    }
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                false
            }
        }
    }


    // ── Soldiers ──────────────────────────────────────────────────
    suspend fun getSoldiers(): List<Soldier> {
        return withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(getRequest("/soldiers/")).execute()
                if (!response.isSuccessful) return@withContext emptyList()

                val array = JSONArray(response.body!!.string())
                val soldiers = mutableListOf<Soldier>()

                for (i in 0 until array.length()) {
                    val s = array.getJSONObject(i)
                    soldiers.add(
                        Soldier(
                            id         = s.getString("id"),
                            name       = s.getString("name"),
                            rankTitle  = s.getString("rank_title"),
                            rankOrder  = s.getInt("rank_order"),
                            serial     = s.getString("serial"),
                            squad      = s.optString("squad_name", "Unknown"),
                            role       = s.getString("role"),
                            hr         = if (s.isNull("hr")) null else s.getInt("hr"),
                            spo2       = if (s.isNull("spo2")) null else s.getInt("spo2"),
                            temp       = if (s.isNull("temp")) null else s.getDouble("temp").toFloat(),
                            battery    = if (s.isNull("battery")) 0 else s.getInt("battery"),
                            status     = s.getString("status"),
                            bloodGroup = s.optString("blood_group", "O+"),
                            photoUri   = if (s.isNull("photo_url")) null else s.getString("photo_url")
                        )
                    )
                }
                soldiers
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    suspend fun createSoldier(soldier: Soldier, squadId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("name",        soldier.name)
                    put("rank_title",  soldier.rankTitle)
                    put("rank_order",  soldier.rankOrder)
                    put("serial",      soldier.serial)
                    put("squad_id",    squadId)
                    put("role",        soldier.role)
                    put("blood_group", soldier.bloodGroup)
                    put("status",      soldier.status)
                }
                val response = client.newCall(postRequest("/soldiers/", body)).execute()
                response.isSuccessful
            } catch (e: Exception) {
                false
            }
        }
    }

    suspend fun updateSoldier(soldier: Soldier): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("name",        soldier.name)
                    put("rank_title",  soldier.rankTitle)
                    put("rank_order",  soldier.rankOrder)
                    put("serial",      soldier.serial)
                    put("role",        soldier.role)
                    put("blood_group", soldier.bloodGroup)
                    put("status",      soldier.status)
                }
                val response = client.newCall(
                    putRequest("/soldiers/${soldier.id}", body)
                ).execute()
                response.isSuccessful
            } catch (e: Exception) {
                false
            }
        }
    }

    suspend fun deleteSoldier(soldierId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(
                    deleteRequest("/soldiers/$soldierId")
                ).execute()
                response.isSuccessful
            } catch (e: Exception) {
                false
            }
        }
    }


    // ── Squads ────────────────────────────────────────────────────
    suspend fun getSquads(): List<Pair<String, String>> {
        return withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(getRequest("/squads/")).execute()
                if (!response.isSuccessful) return@withContext emptyList()

                val array = JSONArray(response.body!!.string())
                val squads = mutableListOf<Pair<String, String>>()

                for (i in 0 until array.length()) {
                    val s = array.getJSONObject(i)
                    squads.add(Pair(s.getString("id"), s.getString("name")))
                }
                squads
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    suspend fun createSquad(name: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply { put("name", name) }
                val response = client.newCall(postRequest("/squads/", body)).execute()
                if (response.isSuccessful) {
                    JSONObject(response.body!!.string()).getString("id")
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }


    // ── Alerts ────────────────────────────────────────────────────
    suspend fun getAlerts(): List<AppAlert> {
        return withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(getRequest("/alerts/")).execute()
                if (!response.isSuccessful) return@withContext emptyList()

                val array = JSONArray(response.body!!.string())
                val alerts = mutableListOf<AppAlert>()

                for (i in 0 until array.length()) {
                    val a = array.getJSONObject(i)
                    alerts.add(
                        AppAlert(
                            id             = a.getString("id"),
                            title          = a.getString("title"),
                            severity       = a.getString("severity"),
                            soldierName    = a.getString("soldier_name"),
                            soldierSerial  = a.getString("soldier_serial"),
                            message        = a.getString("message"),
                            timestamp      = System.currentTimeMillis(),
                            actionRequired = a.getBoolean("action_required")
                        )
                    )
                }
                alerts
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    suspend fun getAlertSummary(): Triple<Int, Int, Int> {
        return withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(
                    getRequest("/alerts/summary/counts")
                ).execute()
                if (!response.isSuccessful) return@withContext Triple(0, 0, 0)

                val json = JSONObject(response.body!!.string())
                Triple(
                    json.getInt("critical"),
                    json.getInt("warning"),
                    json.getInt("total")
                )
            } catch (e: Exception) {
                Triple(0, 0, 0)
            }
        }
    }


    // ── Vitals ────────────────────────────────────────────────────
    suspend fun getLatestVitals(soldierId: String): JSONObject? {
        return withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(
                    getRequest("/vitals/$soldierId/latest")
                ).execute()
                if (response.isSuccessful) {
                    JSONObject(response.body!!.string())
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }


    // ── Map ───────────────────────────────────────────────────────
    suspend fun getLiveMap(): List<MapUpdate> {
        return withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(getRequest("/map/live")).execute()
                if (!response.isSuccessful) return@withContext emptyList()

                val array = JSONArray(response.body!!.string())
                val updates = mutableListOf<MapUpdate>()

                for (i in 0 until array.length()) {
                    val m = array.getJSONObject(i)
                    if (!m.isNull("latitude") && !m.isNull("longitude")) {
                        updates.add(
                            MapUpdate(
                                soldierId = m.getString("soldier_id"),
                                lat       = m.getDouble("latitude"),
                                lng       = m.getDouble("longitude"),
                                status    = m.getString("status")
                            )
                        )
                    }
                }
                updates
            } catch (e: Exception) {
                emptyList()
            }
        }
    }


    // ── Suit Config ───────────────────────────────────────────────
    suspend fun getSuitConfig(soldierId: String): SuitConfig? {
        return withContext(Dispatchers.IO) {
            try {
                var response = client.newCall(getRequest("/suit/$soldierId")).execute()
                if (response.code == 404) {
                    response = client.newCall(
                        putRequest("/suit/$soldierId", JSONObject())
                    ).execute()
                }
                if (!response.isSuccessful) return@withContext null
                suitConfigFromJson(JSONObject(response.body!!.string()))
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun updateSuitConfig(soldierId: String, config: SuitConfig): SuitConfig? {
        return withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("hr_sensor", config.hrSensor)
                    put("spo2_sensor", config.spo2Sensor)
                    put("temp_sensor", config.tempSensor)
                    put("accelerometer", config.accelerometer)
                    put("gps_enabled", config.gpsEnabled)
                    put("sampling_rate_secs", config.samplingRateSecs)
                    put("wifi_enabled", config.wifiEnabled)
                    put("mesh_enabled", config.meshEnabled)
                    put("radio_gateway", config.radioGateway)
                    put("emergency_mode", config.emergencyMode)
                }
                val response = client.newCall(putRequest("/suit/$soldierId", body)).execute()
                if (!response.isSuccessful) return@withContext null
                suitConfigFromJson(JSONObject(response.body!!.string()))
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun resetSuitConfig(soldierId: String): SuitConfig? {
        return withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(
                    postRequest("/suit/$soldierId/reset", JSONObject())
                ).execute()
                if (!response.isSuccessful) return@withContext null
                suitConfigFromJson(JSONObject(response.body!!.string()))
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun setEmergencyMode(soldierId: String, enabled: Boolean): SuitConfig? {
        return withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply { put("enabled", enabled) }
                val response = client.newCall(
                    postRequest("/suit/$soldierId/emergency", body)
                ).execute()
                if (!response.isSuccessful) return@withContext null
                suitConfigFromJson(JSONObject(response.body!!.string()))
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun suitConfigFromJson(json: JSONObject): SuitConfig {
        return SuitConfig(
            hrSensor         = json.getBoolean("hr_sensor"),
            spo2Sensor       = json.getBoolean("spo2_sensor"),
            tempSensor       = json.getBoolean("temp_sensor"),
            accelerometer    = json.getBoolean("accelerometer"),
            gpsEnabled       = json.getBoolean("gps_enabled"),
            samplingRateSecs = json.getInt("sampling_rate_secs"),
            wifiEnabled      = json.getBoolean("wifi_enabled"),
            meshEnabled      = json.getBoolean("mesh_enabled"),
            radioGateway     = json.getBoolean("radio_gateway"),
            emergencyMode    = json.getBoolean("emergency_mode"),
            updatedAt        = parseIsoToMillis(json.optString("updated_at"))
        )
    }

    private fun parseIsoToMillis(iso: String): Long {
        return try {
            val cleaned = iso.substringBefore(".")
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            sdf.parse(cleaned)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
}