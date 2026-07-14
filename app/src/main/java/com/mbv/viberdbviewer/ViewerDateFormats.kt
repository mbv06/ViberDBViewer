package com.mbv.viberdbviewer

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

@Composable
internal fun rememberViewerDateFormats(): ViewerDateFormats {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) {
        ViewerDateFormats(
            time = DateFormat.getTimeFormat(context),
            date = DateFormat.getDateFormat(context),
            longDate = DateFormat.getLongDateFormat(context),
            zoneId = ZoneId.systemDefault(),
        )
    }
}

internal data class ViewerDateFormats(
    val time: java.text.DateFormat,
    val date: java.text.DateFormat,
    val longDate: java.text.DateFormat,
    val zoneId: ZoneId,
) {
    fun formatChatTime(timestamp: Long): String {
        val value = Date(timestamp)
        val messageDay = Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate()
        return if (messageDay == LocalDate.now(zoneId)) {
            time.format(value)
        } else {
            date.format(value)
        }
    }
}
