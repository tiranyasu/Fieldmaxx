package com.fieldmaxx.app.jobs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fieldmaxx.app.net.Api
import com.fieldmaxx.app.net.ApiException
import com.fieldmaxx.app.ui.FmxColors
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal const val NO_SIGNAL = "Can't reach the server. Check your signal."

object LinkTags {
    const val FIELD = "link.field"
    const val CHECK = "link.check"
    const val RESULT = "link.result"
}

/** A check's answer for the link it was made with. */
private class CheckedLink(val url: String, val ok: Boolean, val message: String)

/**
 * The job's OneDrive "Field Notes" share link, with "Check": the server opens the link and answers with the folder's
 * name, or a readable reason it can't be used (sign-in only, expired, no access). The answer shows only while the field
 * still holds the link that was checked. [editable] false keeps the link as it is (it can still be checked).
 */
@Composable
fun FieldNotesLinkField(value: String, onValueChange: (String) -> Unit, api: Api, modifier: Modifier = Modifier, editable: Boolean = true) {
    var checked by remember { mutableStateOf<CheckedLink?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun check() {
        val url = value.trim()
        if (url.isEmpty() || checking) return
        checking = true
        scope.launch(Dispatchers.Main.immediate) { // the answer is shown on the main thread wherever the call finishes
            checked = try {
                val r = api.checkLink(url)
                when {
                    r.ok -> CheckedLink(url, true, r.rootName?.let { "✓ Folder: $it" } ?: "✓ The link works")
                    else -> CheckedLink(url, false, r.error ?: "That link can't be used")
                }
            } catch (e: IOException) {
                CheckedLink(url, false, NO_SIGNAL)
            } catch (e: ApiException) {
                CheckedLink(url, false, e.message)
            } finally {
                checking = false
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("Field Notes link") },
            placeholder = { Text("https://….sharepoint.com/…") },
            singleLine = true,
            enabled = editable,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().testTag(LinkTags.FIELD),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = ::check, enabled = value.isNotBlank() && !checking, modifier = Modifier.testTag(LinkTags.CHECK)) {
                if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Check")
            }
            checked?.takeIf { it.url == value.trim() }?.let {
                Text(
                    it.message,
                    color = if (it.ok) FmxColors.Done else MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f).testTag(LinkTags.RESULT),
                )
            }
        }
    }
}
