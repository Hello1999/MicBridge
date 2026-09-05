package com.jack.micbridge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal object Tone {
    val Good = Color(0xFF147D45)
    val Warn = Color(0xFFC74600)
    val Bad = Color(0xFFB3261E)
    val Muted = Color(0xFF6B6B6B)
}

@Composable
internal fun Section(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Tone.Muted)
            }
            HorizontalDivider()
            content()
        }
    }
}

/** A card whose body is hidden until tapped; used for one-time setup and expert diagnostics. */
@Composable
internal fun CollapsibleSection(
    title: String,
    subtitle: String? = null,
    key: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(key) { mutableStateOf(initiallyExpanded) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Tone.Muted)
                    }
                }
                Text(if (expanded) "收起 ▲" else "展开 ▼", color = Tone.Muted)
            }
            if (expanded) {
                HorizontalDivider()
                content()
            }
        }
    }
}

@Composable
internal fun CheckRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Text(label, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun KeyValue(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(key, color = Tone.Muted, modifier = Modifier.width(140.dp))
        Spacer(Modifier.width(8.dp))
        Text(value, modifier = Modifier.weight(1f))
    }
}
