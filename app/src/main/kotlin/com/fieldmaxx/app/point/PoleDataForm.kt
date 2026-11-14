package com.fieldmaxx.app.point

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fieldmaxx.core.model.Attachment
import com.fieldmaxx.core.model.POLE_TAGS
import com.fieldmaxx.core.model.PoleData
import java.math.BigDecimal

/** The type that has pole data: imported and added poles alike (spec §6.4). */
internal const val POLE_TYPE = "POLE"

/** What the line height field holds: feet or nothing ([Valid]), or text that isn't a height. */
internal sealed interface LineHeight {
    data class Valid(val ft: Double?) : LineHeight

    data object Invalid : LineHeight
}

private val HEIGHT = Regex("""\d{1,3}([.,]\d*)?|[.,]\d+""")

/** "25.5" or "25,5" feet; an empty field is no height. */
internal fun parseLineHeight(text: String): LineHeight {
    val t = text.trim()
    if (t.isEmpty()) return LineHeight.Valid(null)
    if (!HEIGHT.matches(t)) return LineHeight.Invalid
    return LineHeight.Valid(t.replace(',', '.').toDouble())
}

/** 25.0 → "25", 25.5 → "25.5", none → "". */
internal fun formatLineHeight(ft: Double?): String = ft?.let { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() }.orEmpty()

/** [tag] added or taken out, the tags kept in the form's order (any the form doesn't know stay, last). */
internal fun toggleTag(tags: List<String>, tag: String): List<String> {
    val now = if (tag in tags) tags.filter { it != tag } else tags + tag
    return POLE_TAGS.filter { it in now } + now.filter { it !in POLE_TAGS }.distinct()
}

/**
 * Pole data (spec §6.4): line height (ft, decimal), Anchor, Self-supporting or Lashed (one or neither) and the tags.
 * [heightText] is the field as typed and [onHeightText] gets each change; [onChange] gets the whole pole data after a
 * toggle, as it is saved: last write wins on the pole data as one field.
 */
@Composable
internal fun PoleDataForm(pole: PoleData, heightText: String, onHeightText: (String) -> Unit, onChange: (PoleData) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Pole data", style = MaterialTheme.typography.titleMedium)
        val invalid = parseLineHeight(heightText) == LineHeight.Invalid
        OutlinedTextField(
            value = heightText,
            onValueChange = onHeightText,
            label = { Text("Line height (ft)") },
            singleLine = true,
            isError = invalid,
            supportingText = if (invalid) ({ Text("Feet, like 25.5") }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().testTag(PointTags.LINE_HEIGHT),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .toggleable(value = pole.anchor, role = Role.Switch, onValueChange = { onChange(pole.copy(anchor = it)) })
                .testTag(PointTags.ANCHOR),
        ) {
            Text("Anchor", modifier = Modifier.weight(1f))
            Switch(checked = pole.anchor, onCheckedChange = null)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (a in Attachment.entries) {
                Chip(attachmentLabel(a), pole.attachment == a, PointTags.attachment(a)) {
                    onChange(pole.copy(attachment = if (pole.attachment == a) null else a))
                }
            }
        }
        Text("Tags", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in POLE_TAGS) Chip(t, t in pole.tags, PointTags.tag(t)) { onChange(pole.copy(tags = toggleTag(pole.tags, t))) }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) ({ Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }) else null,
        modifier = Modifier.testTag(tag),
    )
}

private fun attachmentLabel(a: Attachment): String = when (a) {
    Attachment.self_supporting -> "Self-supporting"
    Attachment.lashed -> "Lashed"
}
