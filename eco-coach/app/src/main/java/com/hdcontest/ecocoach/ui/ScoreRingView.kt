package com.hdcontest.ecocoach.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.hdcontest.ecocoach.R
import kotlin.math.min

/** 0~100 점수를 원형 게이지로 그린다. 점수 구간에 따라 색이 바뀐다. */
class ScoreRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var score: Int? = null
        set(value) {
            field = value?.coerceIn(0, 100)
            contentDescription = value?.let { "에코 점수 ${it}점" } ?: "점수 없음"
            invalidate()
        }

    var caption: String = ""
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val stroke = 22f * density
    private val arc = RectF()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = ContextCompat.getColor(context, R.color.surface_high)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        color = ContextCompat.getColor(context, R.color.text_primary)
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.text_secondary)
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val radius = size / 2f - stroke
        arc.set(cx - radius, cy - radius, cx + radius, cy + radius)

        canvas.drawArc(arc, 135f, 270f, false, trackPaint)
        val value = score
        if (value != null) {
            progressPaint.color = ContextCompat.getColor(context, colorFor(value))
            canvas.drawArc(arc, 135f, 270f * value / 100f, false, progressPaint)
        }

        scorePaint.textSize = size * 0.30f
        captionPaint.textSize = size * 0.075f
        canvas.drawText(value?.toString() ?: "–", cx, cy + scorePaint.textSize * 0.35f, scorePaint)
        if (caption.isNotEmpty()) canvas.drawText(caption, cx, cy + size * 0.30f, captionPaint)
    }

    companion object {
        fun colorFor(score: Int) = when {
            score >= 90 -> R.color.score_great
            score >= 75 -> R.color.score_good
            score >= 60 -> R.color.score_ok
            else -> R.color.score_bad
        }
    }
}
