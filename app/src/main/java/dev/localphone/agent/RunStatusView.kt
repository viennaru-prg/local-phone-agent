package dev.localphone.agent

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.localphone.core.DisplayOptions
import dev.localphone.core.ResourceUsage

/** Four reused single-line views. No animation, timers or screen reads in the overlay. */
internal class RunStatusView(context: Context) : LinearLayout(context) {
    private val goalLine = line(13f)
    private val taskLine = line(13f)
    private val totalLine = line(12f)
    private val aiLine = line(12f)

    init { orientation = VERTICAL }

    private fun line(size: Float) = TextView(context).apply {
        setTextColor(Color.WHITE); textSize = size; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        addView(this, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun render(goal: String?, task: String, usage: ResourceUsage?, options: DisplayOptions, important: Boolean = false) {
        show(goalLine, options.goal && goal != null, "명령: ${flat(goal.orEmpty())}")
        show(taskLine, options.task || important, if (goal == null) flat(task) else "작업: ${flat(task)}")
        val current = usage ?: ResourceUsage()
        show(totalLine, goal != null && options.total, current.totalLine)
        show(aiLine, goal != null && options.ai, current.aiLine)
        visibility = if ((0 until childCount).any { getChildAt(it).visibility == View.VISIBLE }) View.VISIBLE else View.GONE
    }

    private fun flat(text: String) = text.replace('\n', ' ').replace('\r', ' ').trim()
    private fun show(view: TextView, visible: Boolean, text: String) {
        view.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible && view.text.toString() != text) view.text = text
    }
}
