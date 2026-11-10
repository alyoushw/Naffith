package com.naffith.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * كرة «مرسال» الصوتية: بقع ألوان تتحرك وتندمج مثل Siri/Gemini.
 * تتنفس بهدوء وهي فاضية، وتكبر مع صوتك وهي تسمع، وتدور وهي تفكر، وتنبض وهي تتكلم.
 */
class VoiceOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    var state: State = State.IDLE
        set(value) {
            field = value
            invalidate()
        }

    private val colors = intArrayOf(
        ContextCompat.getColor(context, R.color.orb_blue),
        ContextCompat.getColor(context, R.color.orb_violet),
        ContextCompat.getColor(context, R.color.orb_pink),
        ContextCompat.getColor(context, R.color.orb_teal),
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var time = 0f
    private var level = 0f
    private var targetLevel = 0f
    private var lastFrame = 0L

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val now = System.nanoTime()
            val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1e9f).coerceAtMost(0.05f)
            lastFrame = now
            val speed = when (state) {
                State.IDLE -> 0.6f
                State.LISTENING -> 1.6f + level * 2f
                State.THINKING -> 2.6f
                State.SPEAKING -> 1.8f
            }
            time += dt * speed
            level += (targetLevel - level) * 0.18f
            targetLevel *= 0.9f
            invalidate()
        }
    }

    /** مستوى الصوت من محرك التعرف (بالديسيبل، تقريباً -2 إلى 10). */
    fun setLevel(rmsDb: Float) {
        targetLevel = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrame = 0L
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    private val clip = android.graphics.Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        // نصف القطر الأساسي، ونترك مساحة للهالة والنبض عشان ما تنقص الكرة من الأطراف.
        val base = min(width, height) / 2f * 0.58f

        val breath = when (state) {
            State.IDLE -> 0.03f * sin(time * 1.4f)
            State.LISTENING -> 0.04f * sin(time * 3f) + 0.16f * level
            State.THINKING -> 0.04f * sin(time * 2f)
            State.SPEAKING -> 0.08f * abs(sin(time * 4f))
        }
        val r = base * (1f + breath)

        // 1) هالة خارجية ناعمة ومتمركزة.
        paint.shader = RadialGradient(
            cx, cy, r * 1.6f,
            intArrayOf(withAlpha(colors[1], 110), withAlpha(colors[0], 40), Color.TRANSPARENT),
            floatArrayOf(0.55f, 0.75f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r * 1.6f, paint)

        // 2) الكرة نفسها: دائرة مرتبة، والألوان تدور جواها بدون ما تطلع برا.
        canvas.save()
        clip.reset()
        clip.addCircle(cx, cy, r, android.graphics.Path.Direction.CW)
        canvas.clipPath(clip)
        paint.shader = RadialGradient(
            cx, cy, r, intArrayOf(colors[1], colors[0]), null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, paint)
        for (i in colors.indices) {
            val phase = i * (Math.PI.toFloat() / 2f)
            val orbit = r * (0.38f + 0.10f * level)
            val bx = cx + cos(time * (0.8f + i * 0.15f) + phase) * orbit
            val by = cy + sin(time * (0.8f + i * 0.15f) + phase) * orbit
            val br = r * 0.85f
            paint.shader = RadialGradient(
                bx, by, br,
                intArrayOf(withAlpha(colors[i], 235), withAlpha(colors[i], 90), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawCircle(bx, by, br, paint)
        }
        // لمعة زجاجية فوق.
        paint.shader = RadialGradient(
            cx - r * 0.3f, cy - r * 0.35f, r * 0.75f,
            intArrayOf(withAlpha(Color.WHITE, 120), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, paint)
        canvas.restore()

        // 3) حافة خفيفة تحدد الدائرة.
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.03f
        paint.color = withAlpha(Color.WHITE, 45)
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE // الشفافية تأثر على التدرجات في الإطار الجاي، فنرجعها كاملة
    }

    private fun withAlpha(color: Int, alpha: Int) =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
