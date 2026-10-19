package com.antigravity.monarch.ui.screens

import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.antigravity.monarch.ui.components.SystemPanel
import com.antigravity.monarch.ui.theme.MonarchColors
import kotlinx.coroutines.flow.Flow
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

fun dayShort(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, Locale.getDefault())
fun dayLong(day: DayOfWeek): String = day.getDisplayName(TextStyle.FULL, Locale.getDefault())
fun formatTime(time: LocalTime): String = time.format(DateTimeFormatter.ofPattern("HH:mm"))

@Composable
fun TimePickerField(label: String, time: LocalTime, onPicked: (LocalTime) -> Unit) {
    val context = LocalContext.current
    SystemPanel(
        modifier = Modifier.clickable {
            TimePickerDialog(context, { _, h, m -> onPicked(LocalTime.of(h, m)) }, time.hour, time.minute, true).show()
        },
        contentPadding = 14.dp,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MonarchColors.TextDim)
        Text(formatTime(time), style = MaterialTheme.typography.titleLarge, color = MonarchColors.Rim)
    }
}

/** Collects one-shot UI messages into the shared snackbar host. */
@Composable
fun MessageEffect(messages: Flow<String>, snackbar: SnackbarHostState) {
    LaunchedEffect(messages) {
        messages.collect { snackbar.showSnackbar(it) }
    }
}

@Composable
fun EmptyNote(text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MonarchColors.TextDim)
    }
}
