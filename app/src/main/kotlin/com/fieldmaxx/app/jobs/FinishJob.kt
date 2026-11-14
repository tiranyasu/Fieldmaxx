package com.fieldmaxx.app.jobs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fieldmaxx.app.net.Api
import com.fieldmaxx.app.net.ApiException
import com.fieldmaxx.app.ui.FmxColors
import com.fieldmaxx.core.model.FinishResult
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val FINISH_INFO = "Once every point is done and every photo is in OneDrive, the server writes the pole data into the " +
    "job's Field Notes folder and moves the job to Finished. Finished jobs stay viewable."
private const val FINISH_NEEDS_SIGNAL = "Can't reach the server. Finishing a job needs signal."

object FinishTags {
    const val CONFIRM = "finish.confirm"
    const val MESSAGE = "finish.message"
    const val OK = "finish.ok"
}

/** What still stands between the job and Finished: "7 points left, 3 photos not in OneDrive yet". */
fun finishBlockedMessage(r: FinishResult): String = listOfNotNull(
    r.pointsLeft.takeIf { it > 0 }?.let { if (it == 1) "1 point left" else "$it points left" },
    r.photosPending.takeIf { it > 0 }?.let { if (it == 1) "1 photo not in OneDrive yet" else "$it photos not in OneDrive yet" },
).joinToString(", ").ifEmpty { "Not finished yet" }

/**
 * Asks the server to finish [jobId] (spec §6.10). Once it is finished the job is fetched again ([refresh], best effort:
 * the crew's phones hear of it anyway), so the phone shows it Finished. Throws [IOException] or [ApiException].
 */
suspend fun finishJob(api: Api, refresh: suspend (jobId: String) -> Unit, jobId: String): FinishResult {
    val r = api.finishJob(jobId)
    if (r.finished) {
        try {
            refresh(jobId)
        } catch (e: IOException) {
            // the job's change reaches the phone with the next sync
        } catch (e: ApiException) {
            // the same
        }
    }
    return r
}

private sealed interface Finishing {
    data object Asking : Finishing
    data object Busy : Finishing
    data object Done : Finishing
    class Blocked(val message: String) : Finishing
    class Failed(val message: String) : Finishing
}

/**
 * Menu → Finish this job: it says what finishing does and asks first; then shows what is unfinished (points left,
 * photos not in OneDrive yet), or that the job is finished.
 */
@Composable
fun FinishJobDialog(jobName: String, finish: suspend () -> FinishResult, onDismiss: () -> Unit) {
    var state by remember { mutableStateOf<Finishing>(Finishing.Asking) }
    val scope = rememberCoroutineScope()
    val s = state
    AlertDialog(
        onDismissRequest = { if (s != Finishing.Busy) onDismiss() },
        title = {
            Text(
                when (s) {
                    Finishing.Done -> "$jobName is finished"
                    is Finishing.Blocked -> "$jobName isn't finished yet"
                    else -> "Finish $jobName?"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (s) {
                    Finishing.Asking, Finishing.Busy -> Text(FINISH_INFO)
                    Finishing.Done -> Text("Its pole data is in the job's Field Notes folder.")
                    is Finishing.Blocked -> {
                        Text(s.message, color = FmxColors.Warning, modifier = Modifier.testTag(FinishTags.MESSAGE))
                        Text("Finish it again once they are done.")
                    }
                    is Finishing.Failed -> Text(s.message, color = FmxColors.Warning, modifier = Modifier.testTag(FinishTags.MESSAGE))
                }
            }
        },
        confirmButton = {
            when (s) {
                Finishing.Asking, Finishing.Busy -> TextButton(
                    onClick = {
                        state = Finishing.Busy
                        scope.launch(Dispatchers.Main.immediate) {
                            state = try {
                                val r = finish()
                                if (r.finished) Finishing.Done else Finishing.Blocked(finishBlockedMessage(r))
                            } catch (e: IOException) {
                                Finishing.Failed(FINISH_NEEDS_SIGNAL)
                            } catch (e: ApiException) {
                                Finishing.Failed(e.message)
                            }
                        }
                    },
                    enabled = s == Finishing.Asking,
                    modifier = Modifier.testTag(FinishTags.CONFIRM),
                ) { Text("Finish") }
                else -> TextButton(onClick = onDismiss, modifier = Modifier.testTag(FinishTags.OK)) { Text("OK") }
            }
        },
        dismissButton = if (s == Finishing.Asking || s == Finishing.Busy) ({ TextButton(onClick = onDismiss, enabled = s == Finishing.Asking) { Text("Cancel") } }) else null,
    )
}
