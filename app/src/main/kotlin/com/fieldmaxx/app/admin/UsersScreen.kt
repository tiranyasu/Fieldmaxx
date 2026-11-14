package com.fieldmaxx.app.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fieldmaxx.app.net.Api
import com.fieldmaxx.app.net.ApiException
import com.fieldmaxx.core.model.Role
import com.fieldmaxx.core.model.User
import java.io.IOException
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** No 0/o or 1/l/i: a password read out or written down without mix-ups. */
private const val PASSWORD_LETTERS = "abcdefghjkmnpqrstuvwxyz23456789"
private val random = SecureRandom()

object UsersTags {
    const val NEW = "users.new"
    const val DISPLAY_NAME = "users.new.name"
    const val USERNAME = "users.new.username"
    const val ADMIN_ROLE = "users.new.admin"
    const val CREATE = "users.new.create"
    const val TEMP_PASSWORD = "users.tempPassword"
    const val TEMP_DONE = "users.tempPassword.done"
    const val CONFIRM = "users.confirm"
    fun menu(userId: String) = "users.menu:$userId"
}

/** A temporary password: three groups of four letters and digits, like "kq7m-x3pa-9tzr" (about 59 bits of chance). */
fun temporaryPassword(): String =
    List(3) { List(4) { PASSWORD_LETTERS[random.nextInt(PASSWORD_LETTERS.length)] }.joinToString("") }.joinToString("-")

/** "alice · Tech", "bob · Admin · Disabled". */
internal fun userLine(u: User): String =
    listOfNotNull(u.username, if (u.role == Role.admin) "Admin" else "Tech", "Disabled".takeIf { u.disabled }).joinToString(" · ")

/** A temporary password just set, for [user]. Its text never shows in logs: [toString] leaves it out. */
class OneTimePassword(val user: User, val password: String) {
    override fun toString() = "OneTimePassword(${user.username})"
}

/**
 * Keeps the temporary password on screen across a rotation, in memory only: never in saved state (which Android may
 * keep), never on the disk. "Done" drops it.
 */
class OneTimePasswordHolder : ViewModel() {
    var shown by mutableStateOf<OneTimePassword?>(null)
}

/** What an admin can do to another user, each confirmed first (spec §6.1). */
private enum class UserAction(val label: String) {
    RESET("Reset password"),
    PROMOTE("Make admin"),
    DEMOTE("Make tech"),
    DISABLE("Disable"),
    ENABLE("Enable"),
}

private fun UserAction.title(u: User) = when (this) {
    UserAction.RESET -> "Reset ${u.displayName}'s password?"
    UserAction.PROMOTE -> "Make ${u.displayName} an admin?"
    UserAction.DEMOTE -> "Make ${u.displayName} a tech?"
    UserAction.DISABLE -> "Disable ${u.displayName}?"
    UserAction.ENABLE -> "Enable ${u.displayName}?"
}

private fun UserAction.explanation(u: User) = when (this) {
    UserAction.RESET -> "${u.displayName} is signed out on every phone and signs in with a new temporary password, shown once."
    UserAction.PROMOTE -> "Admins manage everything: users, every job, delivery health and the settings."
    UserAction.DEMOTE -> "${u.displayName} keeps working jobs, without the admin screens."
    UserAction.DISABLE -> "${u.displayName} is signed out on every phone and can't sign in until enabled again."
    UserAction.ENABLE -> "${u.displayName} can sign in again."
}

/**
 * Admin → Users (spec §6.1, §6.9): everyone with their role; create a user, reset a password, disable or enable,
 * promote or make a tech again. A new user's password and a reset's are temporary ones made here ([newPassword]),
 * shown once ([OneTimePasswordHolder]) for the admin to hand over. The admin's own row has none of it: nobody locks
 * themselves out (their own password is Menu → Change password).
 */
@Composable
fun UsersScreen(api: Api, me: suspend () -> User?, onBack: () -> Unit, newPassword: () -> String = { temporaryPassword() }) {
    val oneTime: OneTimePasswordHolder = viewModel()
    var users by remember { mutableStateOf<List<User>?>(null) }
    var self by remember { mutableStateOf<User?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var asking by remember { mutableStateOf<Pair<UserAction, User>?>(null) }
    val scope = rememberCoroutineScope()

    // Main-confined, as on the other screens: the state is written on the main thread wherever a call finishes.
    LaunchedEffect(reload) {
        withContext(Dispatchers.Main.immediate) {
            self = me()
            try {
                users = api.adminUsers()
                problem = null
            } catch (e: IOException) {
                problem = ADMIN_NEEDS_SIGNAL
            } catch (e: ApiException) {
                problem = e.message
            }
        }
    }

    fun run(action: UserAction, u: User) {
        scope.launch(Dispatchers.Main.immediate) {
            try {
                when (action) {
                    UserAction.RESET -> {
                        val password = newPassword()
                        api.patchUser(u.id, password = password)
                        oneTime.shown = OneTimePassword(u, password)
                    }
                    UserAction.PROMOTE -> api.patchUser(u.id, role = Role.admin)
                    UserAction.DEMOTE -> api.patchUser(u.id, role = Role.tech)
                    UserAction.DISABLE -> api.patchUser(u.id, disabled = true)
                    UserAction.ENABLE -> api.patchUser(u.id, disabled = false)
                }
                problem = null
            } catch (e: IOException) {
                problem = ADMIN_NEEDS_SIGNAL
            } catch (e: ApiException) {
                problem = e.message
            }
            reload++
        }
    }

    AdminScaffold(
        "Users", onBack, onRefresh = { reload++ },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New user") },
                modifier = Modifier.testTag(UsersTags.NEW),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            problem?.let { item(key = "problem") { AdminNote(it) } }
            items(users.orEmpty(), key = { it.id }) { u ->
                UserRow(u, isSelf = u.id == self?.id, onAction = { asking = it to u })
            }
        }
    }

    if (creating) {
        NewUserDialog(
            api, newPassword,
            onCreated = { created ->
                creating = false
                oneTime.shown = created
                reload++
            },
            onDismiss = { creating = false },
        )
    }
    asking?.let { (action, u) ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(action.title(u)) },
            text = { Text(action.explanation(u)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        asking = null
                        run(action, u)
                    },
                    modifier = Modifier.testTag(UsersTags.CONFIRM),
                ) { Text(action.label) }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("Cancel") } },
        )
    }
    oneTime.shown?.let { TemporaryPasswordDialog(it, onDone = { oneTime.shown = null }) }
}

@Composable
private fun UserRow(u: User, isSelf: Boolean, onAction: (UserAction) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(u.displayName, color = if (u.disabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface) },
        supportingContent = { Text(userLine(u)) },
        trailingContent = {
            if (isSelf) {
                Text("You", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag(UsersTags.menu(u.id))) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More for ${u.displayName}")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val actions = listOf(
                            UserAction.RESET,
                            if (u.role == Role.admin) UserAction.DEMOTE else UserAction.PROMOTE,
                            if (u.disabled) UserAction.ENABLE else UserAction.DISABLE,
                        )
                        actions.forEach { a ->
                            DropdownMenuItem(
                                text = { Text(a.label) },
                                onClick = {
                                    menu = false
                                    onAction(a)
                                },
                            )
                        }
                    }
                }
            }
        },
    )
    HorizontalDivider()
}

/** New user (spec §6.1): display name, username and role; the password is a temporary one made here. */
@Composable
private fun NewUserDialog(api: Api, newPassword: () -> String, onCreated: (OneTimePassword) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf(Role.tech) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("New user") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("Display name") }, singleLine = true, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag(UsersTags.DISPLAY_NAME),
                )
                OutlinedTextField(
                    value = username, onValueChange = { username = it }, label = { Text("Username") }, singleLine = true, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag(UsersTags.USERNAME),
                )
                for (r in listOf(Role.tech, Role.admin)) {
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = role == r, enabled = !busy, role = SemanticsRole.RadioButton) { role = r }
                            .then(if (r == Role.admin) Modifier.testTag(UsersTags.ADMIN_ROLE) else Modifier),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = role == r, onClick = null, enabled = !busy)
                        Text(if (r == Role.admin) "Admin" else "Tech")
                    }
                }
                Text("A temporary password is made for them and shown once.", style = MaterialTheme.typography.bodySmall)
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank() || username.isBlank()) {
                        problem = "Enter the display name and the username"
                        return@TextButton
                    }
                    busy = true
                    scope.launch(Dispatchers.Main.immediate) {
                        try {
                            val password = newPassword()
                            val user = api.createUser(username.trim(), name.trim(), password, role)
                            onCreated(OneTimePassword(user, password))
                        } catch (e: IOException) {
                            problem = ADMIN_NEEDS_SIGNAL
                        } catch (e: ApiException) {
                            problem = e.message // "That username is taken", say
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.testTag(UsersTags.CREATE),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

/**
 * The temporary password, shown once. Only "Done" closes it (a stray tap outside must not lose it), and the window is
 * secure: no screenshot, and no picture of it in the recent apps, which Android keeps on the disk.
 */
@Composable
private fun TemporaryPasswordDialog(p: OneTimePassword, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("Temporary password for ${p.user.displayName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(p.password, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag(UsersTags.TEMP_PASSWORD))
                Text("Give it to ${p.user.displayName} with the username ${p.user.username}. It isn't shown again: if it gets lost, reset the password.")
            }
        },
        confirmButton = { TextButton(onClick = onDone, modifier = Modifier.testTag(UsersTags.TEMP_DONE)) { Text("Done") } },
    )
}
