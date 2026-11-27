package com.wmods.wppenhacer.adapter

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.xposed.core.db.MessageHistoryStore
import com.wmods.wppenhacer.xposed.utils.DesignUtils
import com.wmods.wppenhacer.xposed.utils.Utils

class MessageAdapter(
    context: Context,
    private val items: List<MessageHistoryStore.MessageItem>
) : ArrayAdapter<MessageHistoryStore.MessageItem>(
    context,
    android.R.layout.simple_list_item_2,
    android.R.id.text1,
    items
) {
    override fun getCount(): Int = items.size

    override fun getItem(position: Int): MessageHistoryStore.MessageItem = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = super.getView(position, convertView, parent)
        val message = items[position]
        val messageView = row.findViewById<TextView>(android.R.id.text1)
        messageView.textSize = 14f
        messageView.setTextColor(DesignUtils.getPrimaryTextColor())
        messageView.text = if (position > 0) {
            formatDiff(items[position - 1].message, message.message)
        } else {
            message.message
        }

        val timestampView = row.findViewById<TextView>(android.R.id.text2)
        timestampView.textSize = 12f
        timestampView.alpha = 0.75f
        timestampView.setTypeface(null, Typeface.ITALIC)
        timestampView.setTextColor(DesignUtils.getPrimaryTextColor())
        timestampView.text = if (message.timestamp == 0L) {
            context.getString(R.string.message_original)
        } else {
            "✏️ ${Utils.getDateTimeFromMillis(message.timestamp)}"
        }
        return row
    }

    private fun formatDiff(oldText: String, newText: String): CharSequence {
        val chunks = EditHistoryDiff.compute(oldText, newText)
        if (chunks.isEmpty()) return newText
        val builder = SpannableStringBuilder()
        for (chunk in chunks) {
            val start = builder.length
            builder.append(chunk.text)
            val end = builder.length
            when (chunk.type) {
                EditHistoryDiff.Type.REMOVED -> {
                    builder.setSpan(
                        ForegroundColorSpan(COLOR_REMOVED),
                        start,
                        end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    builder.setSpan(
                        StrikethroughSpan(),
                        start,
                        end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                EditHistoryDiff.Type.ADDED -> {
                    builder.setSpan(
                        ForegroundColorSpan(COLOR_ADDED),
                        start,
                        end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    builder.setSpan(
                        StyleSpan(Typeface.BOLD),
                        start,
                        end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                EditHistoryDiff.Type.UNCHANGED -> Unit
            }
        }
        return builder
    }

    companion object {
        private val COLOR_REMOVED = Color.parseColor("#E53935")
        private val COLOR_ADDED = Color.parseColor("#43A047")
    }
}
