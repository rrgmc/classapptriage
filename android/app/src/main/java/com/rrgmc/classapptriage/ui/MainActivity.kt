package com.rrgmc.classapptriage.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rrgmc.classapptriage.notify.ImportantNotifier
import com.rrgmc.classapptriage.ui.entity.EntityPickerScreen
import com.rrgmc.classapptriage.ui.login.LoginScreen
import com.rrgmc.classapptriage.ui.messages.MessageDetailScreen
import com.rrgmc.classapptriage.ui.messages.MessagesScreen
import com.rrgmc.classapptriage.ui.messages.MessagesViewModel
import com.rrgmc.classapptriage.ui.rules.RuleDraft
import com.rrgmc.classapptriage.ui.rules.RuleEditScreen
import com.rrgmc.classapptriage.ui.rules.RulesScreen
import com.rrgmc.classapptriage.ui.theme.ClassAppTriageTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** A message to open, from a tapped notification; cleared once shown. */
    private val openMessage = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            ClassAppTriageTheme { AppRoot(openMessage) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val id = intent.getLongExtra(ImportantNotifier.EXTRA_MESSAGE_ID, 0)
        if (id != 0L) openMessage.value = id
    }
}

/** Shows the login screen while logged out, the main navigation otherwise. */
@Composable
fun AppRoot(openMessage: MutableStateFlow<Long?>) {
    val container = appContainer()
    val session by container.tokenStore.session.collectAsState()
    if (session == null) {
        LoginScreen()
    } else {
        MainNav(openMessage)
    }
}

private object Routes {
    const val ENTITIES = "entities"
    const val MESSAGES = "messages"
    const val RULES = "rules"
    const val MESSAGE = "message/{id}"

    fun message(id: Long) = "message/$id"
    const val RULE_EDIT = "rule?group={group}&index={index}&label={label}&labelId={labelId}&sender={sender}&senderId={senderId}"

    fun ruleEdit(draft: RuleDraft): String = buildString {
        append("rule?group=${draft.group}&index=${draft.index}")
        draft.label?.let { append("&label=${Uri.encode(it)}") }
        draft.labelId?.let { append("&labelId=$it") }
        draft.sender?.let { append("&sender=${Uri.encode(it)}") }
        draft.senderId?.let { append("&senderId=$it") }
    }
}

@Composable
private fun MainNav(openMessage: MutableStateFlow<Long?>) {
    val container = appContainer()
    val nav = rememberNavController()
    val entity by container.settings.entity.collectAsState()
    // Decided once: later inbox changes navigate explicitly.
    val start = remember { if (entity == null) Routes.ENTITIES else Routes.MESSAGES }
    val pendingMessage by openMessage.collectAsState()
    LaunchedEffect(pendingMessage) {
        val id = pendingMessage ?: return@LaunchedEffect
        openMessage.value = null
        // Notifications are for the selected inbox; without one, stay on the picker.
        if (entity == null) return@LaunchedEffect
        nav.navigate(Routes.message(id)) { popUpTo(Routes.MESSAGES) }
    }
    NavHost(navController = nav, startDestination = start) {
        composable(Routes.ENTITIES) {
            EntityPickerScreen(
                canGoBack = nav.previousBackStackEntry != null,
                onBack = { nav.popBackStack() },
                onSelected = {
                    nav.navigate(Routes.MESSAGES) { popUpTo(nav.graph.id) { inclusive = true } }
                },
            )
        }
        composable(Routes.MESSAGES) {
            MessagesScreen(
                onSwitchInbox = { nav.navigate(Routes.ENTITIES) },
                onRules = { nav.navigate(Routes.RULES) },
                onCreateRule = { nav.navigate(Routes.ruleEdit(it)) },
                onOpen = { nav.navigate(Routes.message(it)) },
            )
        }
        composable(Routes.MESSAGE, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            // Shares the list's view model so actions here update the list.
            val listEntry = remember(entry) { nav.getBackStackEntry(Routes.MESSAGES) }
            MessageDetailScreen(
                id = entry.arguments?.getLong("id") ?: 0,
                listVm = appViewModel(owner = listEntry) { MessagesViewModel(it) },
                // Also called after an async delete: only pop while this screen is on top.
                onBack = { if (nav.currentBackStackEntry == entry) nav.popBackStack() },
            )
        }
        composable(Routes.RULES) {
            RulesScreen(
                onBack = { nav.popBackStack() },
                onEdit = { nav.navigate(Routes.ruleEdit(it)) },
            )
        }
        composable(
            Routes.RULE_EDIT,
            arguments = listOf(
                navArgument("group") { type = NavType.StringType; defaultValue = "routine" },
                navArgument("index") { type = NavType.IntType; defaultValue = -1 },
                navArgument("label") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("labelId") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("sender") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("senderId") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            val args = entry.arguments
            val draft = RuleDraft(
                group = args?.getString("group") ?: "routine",
                index = args?.getInt("index") ?: -1,
                label = args?.getString("label"),
                labelId = args?.getString("labelId")?.toLongOrNull(),
                sender = args?.getString("sender"),
                senderId = args?.getString("senderId")?.toLongOrNull(),
            )
            RuleEditScreen(draft = draft, onDone = { nav.popBackStack() })
        }
    }
}
