package com.rrgmc.classapptriage

import android.app.Application
import android.content.res.Resources
import com.rrgmc.classapptriage.api.ClassAppClient
import com.rrgmc.classapptriage.data.RulesStore
import com.rrgmc.classapptriage.data.Session
import com.rrgmc.classapptriage.data.SettingsStore
import com.rrgmc.classapptriage.data.TokenStore
import com.rrgmc.classapptriage.notify.ImportantNotifier
import com.rrgmc.classapptriage.notify.NotifyState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.TimeZone

/** Manual dependency container shared by the view models. */
class AppContainer(private val app: Application) {
    val tokenStore = TokenStore(app)
    val rulesStore = RulesStore(app)
    val settings = SettingsStore(app)
    val notifyState = NotifyState(app)

    /** Resources in the current app language, for view-model messages. */
    val res: Resources get() = app.resources

    /**
     * An unauthenticated client for the login flow. The API locale follows the
     * app language so server messages match the UI; the time zone follows the
     * device.
     */
    val loginClient: ClassAppClient
        get() = ClassAppClient(locale = apiLocale(), tzOffset = tzOffsetMinutes())

    /** A client for the current session, or null when logged out. */
    fun client(): ClassAppClient? = tokenStore.session.value?.let { loginClient.withToken(it.accessToken) }

    private val _sessionExpired = MutableStateFlow(false)

    /** True after the server rejected the stored token, until the next login. */
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    /**
     * Stores the token from a successful login. The login form is only shown
     * while no token is stored. The selected inbox is kept when the same
     * account logs in again.
     */
    fun login(session: Session) {
        if (!settings.lastLogin.equals(session.login, ignoreCase = true)) settings.selectEntity(null)
        settings.lastLogin = session.login
        _sessionExpired.value = false
        tokenStore.save(session)
        if (settings.notifyImportant.value) ImportantNotifier.schedule(app)
    }

    /** Turns the hourly Important-message check on or off. */
    fun setNotifyImportant(on: Boolean) {
        settings.setNotifyImportant(on)
        if (on) {
            ImportantNotifier.schedule(app)
        } else {
            ImportantNotifier.cancel(app)
            notifyState.clear()
        }
    }

    private fun apiLocale(): String =
        if (res.configuration.locales[0].language == "pt") "pt" else "en"

    private fun tzOffsetMinutes(): Int = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

    /** Explicit logout: forgets the token and the selected inbox. Rules are kept. */
    fun logout() {
        tokenStore.clear()
        settings.selectEntity(null)
        ImportantNotifier.cancel(app)
        notifyState.clear()
    }

    /**
     * The server rejected the stored token (HTTP 401): drop it so the login
     * form is shown, but keep the selected inbox for the same account.
     */
    fun sessionExpired() {
        _sessionExpired.value = true
        tokenStore.clear()
    }
}

class ClassAppTriageApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ImportantNotifier.ensureChannel(this)
        if (container.settings.notifyImportant.value && container.tokenStore.session.value != null) {
            ImportantNotifier.schedule(this)
        }
    }
}
