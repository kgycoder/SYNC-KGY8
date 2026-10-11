package com.sync.app.glass

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/**
 * Frontend(WebView) <-> Native(Compose) channel.
 *
 *  JS  -> Kotlin : window.GlassBridge.push(json)   (DOM state mirror, rects in CSS px)
 *  Kotlin -> JS  : window.__glassCmd({...})        (taps / text input forwarded to the existing app.js functions)
 *
 * The WebView + app.js + AndroidBridge keep running exactly as before; they remain the single source of
 * truth for playback / search / playlists. Compose only renders the glass surfaces and their controls.
 */
class GlassController(private val webView: WebView) {

    var state by mutableStateOf(GlassState())
        private set

    /** WebView.getScrollY() (px) — updated on the UI thread by onScrollChanged. */
    var nativeScrollPx by mutableIntStateOf(0)
        private set
    private var nativeWorks = false
    private var nativeNs = 0L

    // JS scroll value (window.scrollY, CSS px). During a fling WebView may deliver onScrollChanged late/in bursts;
    // the page's own scroll events keep coming every frame, so they are used whenever the native value goes stale.
    @Volatile private var jsRaw = 0f
    @Volatile private var jsNs = 0L
    private var jsTick by mutableIntStateOf(0)
    private val jsPosted = java.util.concurrent.atomic.AtomicBoolean(false)

    fun onNativeScroll(y: Int) {
        if (y > 0) nativeWorks = true
        nativeNs = System.nanoTime()
        nativeScrollPx = y
    }

    @JavascriptInterface
    fun scroll(y: Float) {
        jsRaw = y; jsNs = System.nanoTime()
        if (jsPosted.compareAndSet(false, true)) main.post { jsPosted.set(false); jsTick++ } // coalesce to 1 per frame
    }

    /**
     * Current document scroll in CSS px (== dp). Reads snapshot state → callers re-run when it changes.
     * Source: native scrollY while it is fresh (finger down / normal fling); JS scrollY when native went silent.
     */
    fun cssScroll(density: Float): Float {
        @Suppress("UNUSED_VARIABLE") val subscribe = nativeScrollPx + jsTick
        val now = System.nanoTime()
        val nativeFresh = nativeWorks && now - nativeNs < 40_000_000L
        val jsFresh = now - jsNs < 120_000_000L
        return when {
            nativeFresh -> webView.scrollY / density
            jsFresh -> jsRaw
            nativeWorks -> webView.scrollY / density
            else -> jsRaw
        }.coerceAtLeast(0f)
    }

    private val main = Handler(Looper.getMainLooper())
    private var lastJson = ""

    @JavascriptInterface
    fun push(json: String) {
        if (json == lastJson) return
        lastJson = json
        // parse on the JS bridge thread; only the already-built immutable state hops to the UI thread
        val parsed = try { GlassState.parse(json) } catch (_: Exception) { return }
        main.post { state = parsed }
    }

    fun cmd(build: JSONObject.() -> Unit) {
        val js = JSONObject().apply(build).toString()
        webView.post { webView.evaluateJavascript("window.__glassCmd&&window.__glassCmd($js)", null) }
    }

}
