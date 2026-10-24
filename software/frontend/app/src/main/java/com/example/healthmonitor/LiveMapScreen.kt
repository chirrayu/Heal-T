package com.example.healthmonitor

import android.webkit.WebView
import android.webkit.WebViewClient

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*

import androidx.compose.material3.Text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import androidx.compose.ui.viewinterop.AndroidView


@Composable
fun LiveMapScreen() {

    // ⚠ NEW — same pattern as BattlefieldMap: local WebView reference so
    // this screen can push its own updates, register/unregister as the
    // "active" map, center on real GPS, and push real soldier updates
    // instead of showing nothing (previously this screen never called
    // evaluateJavascript with any real data at all — see prior notes).
    var localWebView by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            if (LiveMapState.activeMapWebView.value === localWebView) {
                LiveMapState.activeMapWebView.value = null
            }
        }
    }

    var hasCentered by remember { mutableStateOf(false) }
    LaunchedEffect(LiveMapState.deviceLocation.value, localWebView) {
        val (lat, lng) = LiveMapState.deviceLocation.value ?: return@LaunchedEffect
        val webView = localWebView ?: return@LaunchedEffect
        if (!hasCentered) {
            webView.evaluateJavascript("centerMap($lat, $lng, 15);", null)
            hasCentered = true
        }
    }

    LaunchedEffect(LiveMapState.pendingMapUpdates.size, localWebView) {
        val webView = localWebView ?: return@LaunchedEffect
        // Drain all queued updates so a snapshot seeding N soldiers renders every marker
        while (LiveMapState.pendingMapUpdates.isNotEmpty()) {
            val update = LiveMapState.pendingMapUpdates.removeAt(0)
            val soldierName = SoldierState.soldiers.find { it.id == update.soldierId }
                ?.let { "${it.rankTitle} ${it.name}" } ?: update.soldierId
            val escapedName = soldierName.replace("'", "\\'")
            webView.evaluateJavascript(
                "upsertMarker('${update.soldierId}', '$escapedName', '${update.status}', ${update.lat}, ${update.lng});",
                null
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF07111F))
    ) {

        Text(
            text = "LIVE MAP",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp)
        )

        AndroidView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            factory = { context ->
                WebView(context).apply {

                    settings.javaScriptEnabled = true
                    settings.blockNetworkLoads = false
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    settings.loadsImagesAutomatically = true
                    settings.mixedContentMode =
                        android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                    webChromeClient = android.webkit.WebChromeClient()
                    webViewClient =
                        object : WebViewClient() {

                            override fun onPageFinished(
                                view: WebView?,
                                url: String?
                            ) {
                                super.onPageFinished(view, url)

                                view?.postDelayed({
                                    view.evaluateJavascript(
                                        """
                                        if(window.map){
                                        map.invalidateSize();
                                        }
                                        """,
                                        null
                                    )

                                    // ⚠ NEW — if the GPS fix arrived before
                                    // this page finished loading, center
                                    // immediately rather than waiting for
                                    // deviceLocation to change again.
                                    LiveMapState.deviceLocation.value?.let { (lat, lng) ->
                                        if (!hasCentered) {
                                            view.evaluateJavascript(
                                                "centerMap($lat, $lng, 15);", null
                                            )
                                            hasCentered = true
                                        }
                                    }
                                }, 1000)
                            }
                        }

                    loadUrl("file:///android_asset/live_map.html")

                    localWebView = this
                    LiveMapState.activeMapWebView.value = this
                }
            }
        )
    }
}