package com.fieldmaxx.app.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fieldmaxx.app.net.Api
import com.fieldmaxx.app.net.ApiException
import com.fieldmaxx.app.net.ServerAddress
import com.fieldmaxx.app.net.TokenVault
import com.fieldmaxx.app.net.normalizeServerUrl
import com.fieldmaxx.core.model.User
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object LoginTags {
    const val SERVER = "login.server"
    const val USERNAME = "login.username"
    const val PASSWORD = "login.password"
    const val SUBMIT = "login.submit"
}

/**
 * Server address (prefilled with the last one used, at first [com.fieldmaxx.app.BuildConfig.SERVER_URL]), username and
 * password. A successful sign-in keeps the token in [vault] and calls [onSignedIn]; a failure shows the server's reason.
 */
@Composable
fun LoginScreen(api: Api, vault: TokenVault, server: ServerAddress, onSignedIn: (User) -> Unit) {
    var address by rememberSaveable { mutableStateOf(server.get()) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") } // never put in saved state
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy) return
        val url = normalizeServerUrl(address)
        error = when {
            url == null -> "Enter the server address, like https://fieldmaxx.example.com"
            username.isBlank() || password.isEmpty() -> "Enter your username and password"
            else -> null
        }
        if (url == null || error != null) return
        busy = true
        // Main-confined: navigation and screen state stay on the main thread wherever the network call finishes.
        scope.launch(Dispatchers.Main.immediate) {
            try {
                server.set(url)
                val (token, user) = api.login(username.trim(), password)
                withContext(Dispatchers.IO) { vault.save(token) }
                onSignedIn(user)
            } catch (e: ApiException) {
                error = e.message
            } catch (e: IOException) {
                error = "Can't reach the server. Check the address and your signal."
            } finally {
                busy = false
            }
        }
    }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Fieldmaxx", style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Server address") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag(LoginTags.SERVER),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag(LoginTags.USERNAME),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().testTag(LoginTags.PASSWORD),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { submit() }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag(LoginTags.SUBMIT)) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
                } else {
                    Text("Sign in")
                }
            }
        }
    }
}
