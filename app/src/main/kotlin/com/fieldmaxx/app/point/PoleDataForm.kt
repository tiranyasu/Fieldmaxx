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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fieldmaxx.core.model.Attachment
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

/** A tag typed with "+ New tag" is at most this long. */
internal const val NEW_TAG_MAX = 40

/**
 * The tag chips a point shows: the crew's [listed] tags in their order, then any tag on the point ([onPoint]) that isn't
 * listed, so a tag renamed or deleted since, or added here and not yet listed, still shows and can be taken off.
 */
fun tagChoices(listed: List<String>, onPoint: List<String>): List<String> = listed + onPoint.filter { it !in listed }.distinct()

/** [tag] added or taken out, the tags kept in the chips' [order] (any it doesn't know stay, last). */
internal fun toggleTag(tags: List<String>, tag: String, order: List<String>): List<String> {
    val now = if (tag in tags) tags.filter { it != tag } else tags + tag
    return order.filter { it in now } + now.filter { it !in order }.distinct()
}

/**
 * The point's tags with [typed] added: trimmed, and spelled as the listed tag it matches ignoring case, so the crew's
 * list doesn't grow a second spelling. Unchanged when it is blank or already on the point (in any case).
 */
internal fun addTag(tags: List<String>, typed: String, listed: List<String>): List<String> {
    val t = typed.trim().take(NEW_TAG_MAX)
    if (t.isEmpty() || tags.any { it.equals(t, ignoreCase = true) }) return tags
    val name = listed.firstOrNull { it.equals(t, ignoreCase = true) } ?: t
    return toggleTag(tags, name, tagChoices(listed, tags))
}

/**
 * Pole data (spec §6.4): line height (ft, decimal), Anchor, Self-supporting or Lashed (one or neither). The tags are
 * [TagChips], on every type of point.
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
    }
}

/**
 * The point's tags as chips (any point type): [choices] ([tagChoices]), those in [selected] checked; [onToggle] gets the
 * tag tapped. "+ New tag" asks for a name ([NewTagDialog]) and gives it to [onNew].
 */
@Composable
internal fun TagChips(choices: List<String>, selected: List<String>, onToggle: (String) -> Unit, onNew: (String) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Tags", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in choices) Chip(t, t in selected, PointTags.tag(t)) { onToggle(t) }
            AssistChip(
                onClick = { adding = true },
                label = { Text("+ New tag") },
                modifier = Modifier.testTag(PointTags.NEW_TAG),
            )
        }
    }
    if (adding) {
        NewTagDialog(
            onDismiss = { adding = false },
            onSave = { name ->
                adding = false
                onNew(name)
            },
        )
    }
}

/** One field, [NEW_TAG_MAX] characters at most; the tag goes on the point, and the server adds it to the crew's list. */
@Composable
private fun NewTagDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New tag") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.replace('\n', ' ').take(NEW_TAG_MAX) },
                label = { Text("Tag") },
                supportingText = { Text("The crew's tag list gets it too.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(PointTags.NEW_TAG_FIELD),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank(), modifier = Modifier.testTag(PointTags.NEW_TAG_SAVE)) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
