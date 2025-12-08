package com.example.messenger_app.utils

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object TimeUtils {
    fun formatLastSeen(timestamp: Long): String {
        if (timestamp == 0L) return "Не в сети"
        
        val now = Calendar.getInstance()
        val date = Calendar.getInstance().apply { timeInMillis = timestamp }
        
        val diff = now.timeInMillis - timestamp
        val minutes = diff / (1000 * 60)
        val hours = diff / (1000 * 60 * 60)

        return when {
            now.get(Calendar.DAY_OF_YEAR) == date.get(Calendar.DAY_OF_YEAR) -> {
                val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
                "Был(а) сегодня в ${sdf.format(date.time)}"
            }
            now.get(Calendar.DAY_OF_YEAR) - 1 == date.get(Calendar.DAY_OF_YEAR) -> {
                val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
                "Был(а) вчера в ${sdf.format(date.time)}"
            }
            else -> {
                val sdf = SimpleDateFormat("dd.MM в HH:mm", Locale.getDefault())
                "Был(а) ${sdf.format(date.time)}"
            }
        }
    }
}
