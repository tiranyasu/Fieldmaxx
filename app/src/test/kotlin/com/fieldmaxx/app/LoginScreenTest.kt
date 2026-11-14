package com.fieldmaxx.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.fieldmaxx.app.auth.LoginTags
import com.fieldmaxx.app.net.InMemoryTokenVault
import com.fieldmaxx.app.ui.FieldmaxxNav
import com.fieldmaxx.app.ui.FieldmaxxTheme
import com.fieldmaxx.app.ui.Routes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoginScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val server = MockWebServer()

    private val vault = InMemoryTokenVault()
    private val graph = AppGraph(ApplicationProvider.getApplicationContext(), vault)
    private lateinit var nav: NavHostController

    private fun signIn(password: String) {
        compose.setContent {
            nav = rememberNavController()
            FieldmaxxTheme { FieldmaxxNav(graph, nav) }
        }
        compose.onNodeWithTag(LoginTags.SERVER).performTextReplacement(server.url("/").toString())
        compose.onNodeWithTag(LoginTags.USERNAME).performTextInput("alice")
        compose.onNodeWithTag(LoginTags.PASSWORD).performTextInput(password)
        compose.onNodeWithTag(LoginTags.SUBMIT).performClick()
    }

    @Test fun showsWrongPasswordMessage() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Wrong username or password"}"""))

        signIn("not-it")

        compose.waitUntil(10_000) { compose.onAllNodesWithText("Wrong username or password").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(Routes.LOGIN, nav.currentDestination?.route)
        assertNull(vault.load())
        assertEquals("/api/auth/login", server.takeRequest().path)
    }

    @Test fun successNavigatesToJobs() {
        server.enqueue(
            MockResponse().setBody(
                """{"token":"tok-9","user":{"id":"u1","username":"alice","displayName":"Alice Tech","role":"tech","disabled":false}}""",
            ),
        )

        signIn("right-one")

        compose.waitUntil(10_000) { compose.onAllNodesWithText("Jobs").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Jobs").assertIsDisplayed()
        assertEquals(Routes.JOBS, nav.currentDestination?.route)
        assertNull(nav.previousBackStackEntry, "the login screen is gone from the back stack")
        assertEquals("tok-9", vault.load())
        val request = server.takeRequest()
        assertEquals("/api/auth/login", request.path)
        assertEquals("""{"username":"alice","password":"right-one"}""", request.body.readUtf8())
        assertEquals(server.url("/").toString().trimEnd('/'), graph.server.get(), "the typed server address is kept")
    }
}
