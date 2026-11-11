package com.naffith.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale

/**
 * نافذة تأكيد الموقع قبل ما نرسله: خريطة على موقعك الحالي (GPS)، والدبوس في النص.
 * تقدر تحرّك الخريطة لو الموقع مو دقيق، وبعدين «إرسال» (أو «نسخ»).
 */
class LocationPickActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var accuracyText: TextView
    private lateinit var confirm: Button
    private var locationManager: LocationManager? = null
    private var best: Location? = null
    private var centerLat = Double.NaN
    private var centerLng = Double.NaN
    private var userMoved = false
    private var mapReady = false
    private val main = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.bg))
            layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
        }
        root.addView(TextView(this).apply {
            text = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.loc_title_send)
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setPadding(dp(20), dp(16), dp(20), dp(2))
        })
        accuracyText = TextView(this).apply {
            text = getString(R.string.loc_finding)
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(dp(20), 0, dp(20), dp(10))
        }
        root.addView(accuracyText)

        val mapBox = FrameLayout(this)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(Bridge(), "Android")
            loadDataWithBaseURL("https://naffith.app/", MAP_HTML, "text/html", "utf-8", null)
        }
        mapBox.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // الدبوس ثابت في نص الخريطة: اللي تحته هو اللي ينرسل.
        mapBox.addView(TextView(this).apply {
            text = "📍"
            textSize = 38f
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            bottomMargin = dp(44) // رأس الدبوس على النقطة
        })
        mapBox.addView(Button(this).apply {
            text = getString(R.string.loc_recenter)
            isAllCaps = false
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            background = pill(ContextCompat.getColor(context, R.color.surface_card))
            setOnClickListener {
                userMoved = false
                best?.let { moveTo(it) }
            }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44), Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(14), dp(14), dp(14), dp(14))
        })
        root.addView(mapBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(TextView(this).apply {
            text = getString(R.string.loc_hint)
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(dp(20), dp(10), dp(20), dp(6))
        })
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(4), dp(16), dp(16))
        }
        confirm = Button(this).apply {
            text = intent.getStringExtra(EXTRA_BUTTON) ?: getString(R.string.loc_send)
            isAllCaps = false
            textSize = 17f
            isEnabled = false
            alpha = 0.5f
            setTextColor(0xFFFFFFFF.toInt())
            background = pill(ContextCompat.getColor(context, R.color.brand))
            setOnClickListener { finishWith() }
        }
        buttons.addView(confirm, LinearLayout.LayoutParams(0, dp(56), 2f).apply { marginEnd = dp(10) })
        buttons.addView(Button(this).apply {
            text = getString(R.string.cancel)
            isAllCaps = false
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            background = pill(ContextCompat.getColor(context, R.color.surface_card))
            setOnClickListener { setResult(RESULT_CANCELED); finish() }
        }, LinearLayout.LayoutParams(0, dp(56), 1f))
        root.addView(buttons)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        startLocation()
    }

    private fun pill(color: Int) = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(color) }

    private val listener = LocationListener { loc -> onFix(loc) }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager = lm
        // آخر موقع معروف كبداية سريعة، وبعدين GPS يدقّقه.
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER).forEach { p ->
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.let { if (System.currentTimeMillis() - it.time < 10 * 60_000) onFix(it) }
        }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            runCatching { if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper()) }
        }
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER) && !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            accuracyText.text = getString(R.string.loc_off)
        }
    }

    private fun onFix(loc: Location) {
        val b = best
        // ناخذ الأدق (أو الأحدث بكثير).
        if (b != null && loc.accuracy > b.accuracy && loc.time - b.time < 20_000) return
        best = loc
        accuracyText.text = getString(R.string.loc_accuracy, loc.accuracy.toInt().coerceAtLeast(1))
        if (!userMoved) moveTo(loc)
        // دقة ممتازة: نوقف الـGPS عشان البطارية.
        if (loc.accuracy <= 8) runCatching { locationManager?.removeUpdates(listener) }
    }

    private fun moveTo(loc: Location) {
        centerLat = loc.latitude
        centerLng = loc.longitude
        enableConfirm()
        if (mapReady) {
            web.evaluateJavascript(
                String.format(Locale.US, "setFix(%.7f,%.7f,%.1f)", loc.latitude, loc.longitude, loc.accuracy), null
            )
        }
    }

    private fun enableConfirm() {
        confirm.isEnabled = true
        confirm.alpha = 1f
    }

    private fun finishWith() {
        if (centerLat.isNaN()) return
        setResult(RESULT_OK, Intent().putExtra(EXTRA_LAT, centerLat).putExtra(EXTRA_LNG, centerLng))
        finish()
    }

    inner class Bridge {
        @JavascriptInterface
        fun ready() = main.post {
            mapReady = true
            best?.let { moveTo(it) }
        }

        /** المستخدم سحب الخريطة: نوقف التمركز التلقائي. */
        @JavascriptInterface
        fun dragged() = main.post { userMoved = true }

        @JavascriptInterface
        fun center(lat: Double, lng: Double) = main.post {
            centerLat = lat
            centerLng = lng
            if (userMoved) {
                accuracyText.text = getString(R.string.loc_manual)
                enableConfirm()
            }
        }
    }

    override fun onDestroy() {
        runCatching { locationManager?.removeUpdates(listener) }
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_BUTTON = "button"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"

        /** رابط الموقع اللي ينرسل (يفتح في خرائط Google). */
        fun link(lat: Double, lng: Double) = String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", lat, lng)

        private const val MAP_HTML = """<!doctype html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>html,body,#m{margin:0;height:100%;background:#0A0C12}</style></head><body><div id="m"></div>
<script>
var map=L.map('m',{zoomControl:false,attributionControl:true}).setView([24.7136,46.6753],5);
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(map);
var circle=null,dot=null;
function setFix(lat,lng,acc){
  if(circle){circle.remove();dot.remove();}
  circle=L.circle([lat,lng],{radius:acc,color:'#4F8BFF',weight:1,fillOpacity:0.12}).addTo(map);
  dot=L.circleMarker([lat,lng],{radius:7,color:'#fff',weight:2,fillColor:'#4F8BFF',fillOpacity:1}).addTo(map);
  map.setView([lat,lng],Math.max(map.getZoom(),17));
}
map.on('dragstart',function(){Android.dragged();});
map.on('moveend',function(){var c=map.getCenter();Android.center(c.lat,c.lng);});
Android.ready();
</script></body></html>"""
    }
}
