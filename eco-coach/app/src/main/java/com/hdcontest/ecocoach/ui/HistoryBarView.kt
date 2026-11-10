package com.hdcontest.ecocoach.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.hdcontest.ecocoach.R

/** 최근 주행 점수를 막대로 보여준다 (왼쪽이 오래된 주행, 오른쪽이 최신). */
class HistoryBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var scores: List<Int> = emptyList()

    fun setScores(latestFirst: List<Int>, max: Int = 10) {
        scores = latestFirst.take(max).reversed()
        contentDescription = "최근 ${scores.size}회 주행 점수: ${scores.joinToString()}"
        invalidate()
    }

    private val density = resources.displayMetrics.density
    private val bar = RectF()
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 16f * density
        color = ContextCompat.getColor(context, R.color.text_secondary)
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 20f * density
        color = ContextCompat.getColor(context, R.color.text_secondary)
    }

    override fun onDraw(canvas: Canvas) {
        if (scores.isEmpty()) {
            canvas.drawText(context.getString(R.string.history_empty), width / 2f, height / 2f, emptyPaint)
            return
        }
        val labelSpace = labelPaint.textSize * 1.6f
        val chartHeight = height - labelSpace
        val slot = width / 10f
        val barWidth = slot * 0.55f
        // 막대 수가 적어도 오른쪽(최신) 정렬
        val offset = width - slot * scores.size
        scores.forEachIndexed { i, score ->
            val left = offset + slot * i + (slot - barWidth) / 2
            val top = chartHeight * (1 - score / 100f)
            bar.set(left, top.coerceAtMost(chartHeight - 4 * density), left + barWidth, chartHeight)
            barPaint.color = ContextCompat.getColor(context, ScoreRingView.colorFor(score))
            canvas.drawRoundRect(bar, 6 * density, 6 * density, barPaint)
            canvas.drawText(score.toString(), left + barWidth / 2, height - labelPaint.textSize * 0.3f, labelPaint)
        }
    }
}
