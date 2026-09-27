package com.rrgmc.classapptriage.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.toColorInt
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rrgmc.classapptriage.AppContainer
import com.rrgmc.classapptriage.ClassAppTriageApp
import com.rrgmc.classapptriage.R
import com.rrgmc.classapptriage.api.ClassAppException
import java.io.IOException

/** The app-wide [AppContainer]. */
@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as ClassAppTriageApp).container

/** Creates a view model that receives the [AppContainer]. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    owner: ViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current),
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = appContainer()
    return viewModel(owner, key = key, factory = viewModelFactory { initializer { create(container) } })
}

/**
 * A user-facing description of an error. Server (GraphQL) messages are already
 * localized through the API locale; local failures use string resources.
 */
fun Throwable.userMessage(res: Resources): String = when {
    this is ClassAppException && cause is IOException -> res.getString(R.string.error_network)
    this is ClassAppException -> message ?: res.getString(R.string.error_generic)
    this is IllegalArgumentException -> message ?: res.getString(R.string.error_invalid_input)
    else -> message ?: toString()
}

/** Parses a label color such as "f03e3e" or "#FF8800", or null when unparseable. */
fun parseColor(value: String?): Color? {
    if (value.isNullOrBlank()) return null
    return try {
        // The server sends bare hex ("f03e3e"); accept "#f03e3e" and names too.
        val v = value.trim()
        val hex = Regex("[0-9a-fA-F]{6}|[0-9a-fA-F]{8}")
        Color((if (hex.matches(v)) "#$v" else v).toColorInt())
    } catch (e: IllegalArgumentException) {
        null
    }
}

/** Splits a comma-separated field into trimmed, non-empty values. */
fun splitList(text: String): List<String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

/** Splits a comma-separated field of numeric IDs, ignoring invalid entries. */
fun splitIds(text: String): List<Long> = splitList(text).mapNotNull { it.toLongOrNull() }
