package com.naffith.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

/**
 * «اختصار بخطوات»: تسجّل ضغطاتك داخل تطبيق مرة وحدة، ومرسال يعيدها عنك.
 * كل خطوة نحفظ فيها الكلام المكتوب على الزر (أو وصفه/معرّفه) ومكانه في الشاشة (كنسبة)،
 * وعند التشغيل ندوّر على الزر بالكلام أول، وإذا ما لقيناه نضغط في نفس المكان.
 */
object Macro {

    data class Step(
        val type: String, // "tap" أو "swipe"
        val text: String = "",
        val desc: String = "",
        val viewId: String = "",
        val x: Float = 0f, val y: Float = 0f, // نسبة من عرض/طول الشاشة
        val x2: Float = 0f, val y2: Float = 0f, // نهاية السحب
        val durationMs: Long = 0,
    )

    data class Recording(val packageName: String, val steps: List<Step>)

    fun encode(r: Recording): String = JSONObject()
        .put("pkg", r.packageName)
        .put("steps", JSONArray().apply {
            r.steps.forEach { s ->
                put(
                    JSONObject().put("type", s.type).put("text", s.text).put("desc", s.desc).put("id", s.viewId)
                        .put("x", s.x.toDouble()).put("y", s.y.toDouble()).put("x2", s.x2.toDouble()).put("y2", s.y2.toDouble())
                        .put("d", s.durationMs)
                )
            }
        }).toString()

    fun decode(json: String): Recording? = runCatching {
        val o = JSONObject(json)
        val arr = o.getJSONArray("steps")
        Recording(o.getString("pkg"), (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            Step(
                s.getString("type"), s.optString("text"), s.optString("desc"), s.optString("id"),
                s.optDouble("x").toFloat(), s.optDouble("y").toFloat(),
                s.optDouble("x2").toFloat(), s.optDouble("y2").toFloat(), s.optLong("d")
            )
        })
    }.getOrNull()

    // ---------- أدوات مشتركة ----------

    @Suppress("DEPRECATION")
    private fun screen(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (android.os.Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    /** أصغر عنصر ظاهر تحت النقطة (يعني الزر نفسه)، وأقرب أب ينضغط. */
    private fun nodeAt(root: AccessibilityNodeInfo?, x: Int, y: Int): AccessibilityNodeInfo? {
        root ?: return null
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        val r = Rect()
        fun visit(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40) return
            n.getBoundsInScreen(r)
            if (!r.contains(x, y) || !n.isVisibleToUser) return
            val area = r.width().toLong() * r.height()
            val hasLabel = !n.text.isNullOrBlank() || !n.contentDescription.isNullOrBlank() || n.isClickable
            if (hasLabel && area <= bestArea) {
                best = n
                bestArea = area
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        return best
    }

    private fun labelOf(n: AccessibilityNodeInfo): Pair<String, String> {
        var text = n.text?.toString().orEmpty()
        val desc = n.contentDescription?.toString().orEmpty()
        if (text.isBlank()) {
            // زر بدون نص: ناخذ أول نص داخله.
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                val t = c.text?.toString().orEmpty()
                if (t.isNotBlank()) { text = t; break }
            }
        }
        return text.trim() to desc.trim()
    }

    private fun clickable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur = n
        var hops = 0
        while (cur != null && hops < 8) {
            if (cur.isClickable) return cur
            cur = cur.parent
            hops++
        }
        return null
    }

    private fun tap(service: AccessibilityService, x: Float, y: Float, done: () -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val ok = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = done()
            override fun onCancelled(gestureDescription: GestureDescription?) = done()
        }, null)
        if (!ok) done()
    }

    private fun swipe(service: AccessibilityService, x1: Float, y1: Float, x2: Float, y2: Float, ms: Long, done: () -> Unit) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, ms.coerceIn(120, 1200)))
            .build()
        val ok = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = done()
            override fun onCancelled(gestureDescription: GestureDescription?) = done()
        }, null)
        if (!ok) done()
    }

    // ---------- التسجيل ----------

    /** نتيجة آخر تسجيل، تاخذها شاشة الإعدادات لما ترجع. */
    @Volatile
    var lastRecording: Recording? = null

    private var capture: View? = null
    private var pill: View? = null
    private val steps = mutableListOf<Step>()
    private var recordingPkg = ""

    val isRecording get() = capture != null

    /** يفتح التطبيق ويحط طبقة شفافة تسجّل كل ضغطة (وتمررها للتطبيق) وزر «خلصت». */
    @SuppressLint("ClickableViewAccessibility")
    fun startRecording(service: AccessibilityService, packageName: String): Boolean {
        stopOverlays(service)
        val launch = service.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        steps.clear()
        recordingPkg = packageName
        lastRecording = null
        service.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))

        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = service.resources.displayMetrics.density
        val (w, h) = screen(service)

        val captureParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        val layer = FrameLayout(service).apply { setBackgroundColor(Color.argb(18, 139, 108, 255)) }
        var downX = 0f
        var downY = 0f
        var downAt = 0L
        layer.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; downAt = e.eventTime }
                MotionEvent.ACTION_UP -> {
                    val moved = hypot(e.rawX - downX, e.rawY - downY) > 24 * density
                    // نوقف الطبقة لحظة عشان الضغطة توصل للتطبيق، وبعدها نرجعها.
                    captureParams.flags = captureParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    runCatching { wm.updateViewLayout(layer, captureParams) }
                    val resume = {
                        Handler(Looper.getMainLooper()).postDelayed({
                            captureParams.flags = captureParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                            if (capture != null) runCatching { wm.updateViewLayout(layer, captureParams) }
                        }, 450)
                        Unit
                    }
                    val ux = e.rawX
                    val uy = e.rawY
                    val sx = downX
                    val sy = downY
                    if (!moved) {
                        val node = nodeAt(service.rootInActiveWindow, sx.toInt(), sy.toInt())
                        val (text, desc) = node?.let { labelOf(it) } ?: ("" to "")
                        steps += Step("tap", text, desc, node?.viewIdResourceName.orEmpty(), sx / w, sy / h)
                    } else {
                        val ms = (e.eventTime - downAt).coerceIn(150, 1000)
                        steps += Step("swipe", x = sx / w, y = sy / h, x2 = ux / w, y2 = uy / h, durationMs = ms)
                    }
                    // ننتظر لحظة لين الطبقة توقف تلتقط اللمس، وبعدها نمرر الحركة للتطبيق.
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!moved) tap(service, sx, sy, resume)
                        else swipe(service, sx, sy, ux, uy, (e.eventTime - downAt).coerceIn(150, 1000), resume)
                    }, 90)
                    updatePill(service)
                }
            }
            true
        }

        val pillView = TextView(service).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding((18 * density).toInt(), (10 * density).toInt(), (18 * density).toInt(), (10 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 100 * density
                setColor(Color.argb(235, 255, 92, 138))
            }
            setOnClickListener { finishRecording(service) }
        }
        val pillParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (90 * density).toInt()
        }
        return runCatching {
            wm.addView(layer, captureParams)
            wm.addView(pillView, pillParams)
            capture = layer
            pill = pillView
            updatePill(service)
            true
        }.getOrElse {
            stopOverlays(service)
            false
        }
    }

    private fun updatePill(context: Context) {
        (pill as? TextView)?.text = context.getString(R.string.macro_recording, steps.size)
    }

    private fun finishRecording(service: AccessibilityService) {
        val rec = Recording(recordingPkg, steps.toList())
        stopOverlays(service)
        lastRecording = rec
        // نرجع للإعدادات عشان تكتب الكلام اللي يشغّله.
        service.startActivity(
            Intent(service, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_MACRO_DONE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun stopOverlays(context: Context) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        capture?.let { runCatching { wm.removeView(it) } }
        pill?.let { runCatching { wm.removeView(it) } }
        capture = null
        pill = null
    }

    // ---------- التشغيل ----------

    /** آخر مرة تغيّرت فيها الشاشة (تحدّثها خدمة «مرسال: الإرسال والتحكم» مع كل حدث). */
    @Volatile
    var lastUiChangeAt = 0L

    /** الشاشة هادية (خلصت تحميل) إذا ما تغيّرت آخر [ms]. */
    private fun uiIdle(ms: Long) = android.os.SystemClock.uptimeMillis() - lastUiChangeAt >= ms

    private const val POLL_MS = 120L
    private const val GAP_MS = 250L
    private const val FIND_TIMEOUT_MS = 3000L
    private const val IDLE_MS = 350L
    private const val IDLE_TIMEOUT_MS = 3000L

    /**
     * يفتح التطبيق من أوله ويعيد الخطوات بسرعة: ما ينتظر وقت ثابت، يدوّر على الزر كل جزء من
     * الثانية ويضغطه أول ما يطلع. والخطوات اللي بدون كلام (أو السحب) تنتظر الشاشة تهدأ بس.
     */
    fun play(service: AccessibilityService, rec: Recording, onDone: (Boolean, Recording) -> Unit) {
        val launch = service.packageManager.getLaunchIntentForPackage(rec.packageName)
        if (launch == null) {
            onDone(false, rec)
            return
        }
        // نتعلّم: الخطوة اللي ما انلقت بالكلام نخليها «بالمكان» عشان المرة الجاية ما ندوّر.
        val learned = rec.steps.toMutableList()
        lastUiChangeAt = android.os.SystemClock.uptimeMillis()
        service.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val handler = Handler(Looper.getMainLooper())
        val (w, h) = screen(service)

        fun inApp() = service.rootInActiveWindow?.packageName?.toString() == rec.packageName

        fun runStep(i: Int) {
            if (i >= rec.steps.size) {
                onDone(true, Recording(rec.packageName, learned))
                return
            }
            val step = rec.steps[i]
            val next = { handler.postDelayed({ runStep(i + 1) }, GAP_MS) }
            val started = android.os.SystemClock.uptimeMillis()
            fun elapsed() = android.os.SystemClock.uptimeMillis() - started
            val hasLabel = step.text.isNotBlank() || step.desc.isNotBlank() || step.viewId.isNotBlank()

            /** ننتظر التطبيق يكون قدّام والشاشة تهدأ، وبعدها [action]. */
            fun whenIdle(action: () -> Unit) {
                if ((inApp() && uiIdle(IDLE_MS)) || elapsed() > IDLE_TIMEOUT_MS) action()
                else handler.postDelayed({ whenIdle(action) }, POLL_MS)
            }

            if (step.type == "swipe") {
                whenIdle { swipe(service, step.x * w, step.y * h, step.x2 * w, step.y2 * h, step.durationMs) { next() } }
                return
            }
            if (!hasLabel) {
                whenIdle { tap(service, step.x * w, step.y * h) { next() } }
                return
            }
            fun attempt() {
                val target = find(service, rec.packageName, step)
                when {
                    target != null -> {
                        val c = clickable(target)
                        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            next()
                        } else {
                            val r = Rect()
                            target.getBoundsInScreen(r)
                            tap(service, r.exactCenterX(), r.exactCenterY()) { next() }
                        }
                    }
                    // الصفحة خلصت تحميل والزر ما ينلقى بالكلام (أو انتهت المهلة): نضغط بالمكان على طول،
                    // ونحفظ إن هالخطوة «بالمكان» للمرات الجاية.
                    (inApp() && uiIdle(IDLE_MS) && elapsed() > 700) || elapsed() >= FIND_TIMEOUT_MS -> {
                        learned[i] = step.copy(text = "", desc = "", viewId = "")
                        tap(service, step.x * w, step.y * h) { next() }
                    }
                    else -> handler.postDelayed({ attempt() }, POLL_MS)
                }
            }
            attempt()
        }
        runStep(0)
    }

    private fun find(service: AccessibilityService, pkg: String, step: Step): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        if (root.packageName?.toString() != pkg) return null
        if (step.text.isNotBlank()) {
            root.findAccessibilityNodeInfosByText(step.text)
                ?.filter { it.isVisibleToUser && (it.text?.toString()?.trim() == step.text || it.contentDescription?.toString()?.trim() == step.text) }
                ?.let { matches ->
                    if (step.viewId.isNotBlank()) matches.firstOrNull { it.viewIdResourceName == step.viewId }?.let { return it }
                    matches.firstOrNull()?.let { return it }
                }
        }
        if (step.desc.isNotBlank()) {
            root.findAccessibilityNodeInfosByText(step.desc)
                ?.firstOrNull { it.isVisibleToUser && it.contentDescription?.toString()?.trim() == step.desc }
                ?.let { return it }
        }
        if (step.viewId.isNotBlank() && step.text.isBlank()) {
            val byId = root.findAccessibilityNodeInfosByViewId(step.viewId)?.filter { it.isVisibleToUser }.orEmpty()
            if (byId.size == 1) return byId[0]
            // أكثر من عنصر بنفس المعرّف (قائمة): ناخذ الأقرب لمكان الضغطة الأصلي.
            if (byId.size > 1) {
                val (w, h) = screen(service)
                val r = Rect()
                return byId.minByOrNull {
                    it.getBoundsInScreen(r)
                    abs(r.exactCenterX() - step.x * w) + abs(r.exactCenterY() - step.y * h)
                }
            }
        }
        return null
    }

    fun toastNeedsService(context: Context) =
        Toast.makeText(context, R.string.dev_need_a11y, Toast.LENGTH_LONG).show()
}
