package com.rrgmc.classapptriage.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rrgmc.classapptriage.AppContainer
import com.rrgmc.classapptriage.R
import com.rrgmc.classapptriage.api.Contact
import com.rrgmc.classapptriage.data.Session
import com.rrgmc.classapptriage.ui.appViewModel
import com.rrgmc.classapptriage.ui.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState

data class LoginState(
    val login: String = "",
    val password: String = "",
    val code: String = "",
    /** True once the password was accepted and a one-time code was sent. */
    val awaitingCode: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    /** A successful password login waiting for the system "save password?" prompt. */
    val pendingSave: PendingSave? = null,
)

/** Credentials to offer to the system password manager before completing the login. */
data class PendingSave(val login: String, val password: String, val session: Session)

class LoginViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(LoginState(login = container.settings.lastLogin))

    /** Set when the stored token was rejected; drives the "session expired" note. */
    val sessionExpired = container.sessionExpired
    val state: StateFlow<LoginState> = _state.asStateFlow()

    fun onLogin(v: String) = _state.update { it.copy(login = v, error = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, error = null) }
    /** Codes may contain letters and digits; case is kept, whitespace dropped. */
    fun onCode(v: String) = _state.update { it.copy(code = v.filterNot(Char::isWhitespace), error = null) }

    fun submitPassword() = run {
        val s = _state.value
        val contact = Contact.parse(s.login)
        val client = container.loginClient
        val res = client.loginWithPassword(contact, s.password)
        if (res.requiresOtp) {
            client.sendCode(contact)
            _state.update {
                it.copy(awaitingCode = true, error = null, info = container.res.getString(R.string.login_code_sent, contact.address))
            }
        } else {
            finish(res.token!!, res.refreshToken)
        }
    }

    fun submitCode() = run {
        val s = _state.value
        val res = container.loginClient.loginWithCode(Contact.parse(s.login), s.code)
        finish(res.token!!, res.refreshToken)
    }

    fun resendCode() = run {
        val contact = Contact.parse(_state.value.login)
        container.loginClient.sendCode(contact)
        _state.update { it.copy(info = container.res.getString(R.string.login_code_resent, contact.address)) }
    }

    fun back() = _state.update { it.copy(awaitingCode = false, code = "", password = "", error = null, info = null) }

    /** Signs in with credentials the user picked from the system password manager. */
    fun useSavedCredential(login: String, password: String) {
        _state.update { it.copy(login = login, password = password, error = null) }
        submitPassword()
    }

    private fun finish(token: String, refreshToken: String?) {
        val s = _state.value
        val session = Session(accessToken = token, refreshToken = refreshToken, login = s.login.trim())
        // Let the screen offer the password to the system password manager
        // first: completing the login removes the login screen.
        _state.update { it.copy(code = "", pendingSave = PendingSave(session.login, s.password, session)) }
    }

    /** Stores the token after the save prompt was handled. The password is dropped from memory. */
    fun completeLogin() {
        val pending = _state.value.pendingSave ?: return
        _state.update { it.copy(password = "", pendingSave = null) }
        container.login(pending.session)
    }

    private fun run(block: suspend () -> Unit) {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.userMessage(container.res)) }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }
}

@Composable
fun LoginScreen() {
    val vm = appViewModel { LoginViewModel(it) }
    val state by vm.state.collectAsState()
    val expired by vm.sessionExpired.collectAsState()
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val credentials = remember(context) { CredentialManager.create(context) }

    // Offer saved passwords (system "use saved password?" sheet) once per showing.
    var offered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (offered || state.awaitingCode) return@LaunchedEffect
        offered = true
        try {
            val result = credentials.getCredential(context, GetCredentialRequest(listOf(GetPasswordOption())))
            (result.credential as? PasswordCredential)?.let { vm.useSavedCredential(it.id, it.password) }
        } catch (e: GetCredentialException) {
            // Nothing saved, dismissed, or no provider: type the credentials instead.
        }
    }

    // After a successful password login, offer to save it, then finish logging in.
    LaunchedEffect(state.pendingSave) {
        val pending = state.pendingSave ?: return@LaunchedEffect
        try {
            if (pending.password.isNotEmpty()) {
                credentials.createCredential(context, CreatePasswordRequest(pending.login, pending.password))
            }
        } catch (e: CreateCredentialException) {
            // Declined or no provider: the login still completes.
        } finally {
            vm.completeLogin()
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.login_subtitle),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = state.login,
                onValueChange = vm::onLogin,
                label = { Text(stringResource(R.string.login_contact)) },
                singleLine = true,
                enabled = !state.awaitingCode && !state.loading,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            if (!state.awaitingCode) {
                if (expired) {
                    Text(
                        stringResource(R.string.login_session_expired),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedTextField(
                    value = state.password,
                    onValueChange = vm::onPassword,
                    label = { Text(stringResource(R.string.login_password)) },
                    singleLine = true,
                    enabled = !state.loading,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { vm.submitPassword() }),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = stringResource(if (showPassword) R.string.login_hide_password else R.string.login_show_password),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = vm::submitPassword,
                    enabled = !state.loading && state.login.isNotBlank() && state.password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.login_sign_in)) }
            } else {
                state.info?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                OutlinedTextField(
                    value = state.code,
                    onValueChange = vm::onCode,
                    label = { Text(stringResource(R.string.login_code)) },
                    singleLine = true,
                    enabled = !state.loading,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { vm.submitCode() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = vm::submitCode,
                    enabled = !state.loading && state.code.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.login_verify)) }
                TextButton(onClick = vm::resendCode, enabled = !state.loading) { Text(stringResource(R.string.login_resend)) }
                TextButton(onClick = vm::back, enabled = !state.loading) { Text(stringResource(R.string.login_other_account)) }
            }
            if (state.loading) CircularProgressIndicator(modifier = Modifier.size(32.dp))
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
