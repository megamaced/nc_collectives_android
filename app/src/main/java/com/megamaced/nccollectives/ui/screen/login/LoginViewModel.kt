package com.megamaced.nccollectives.ui.screen.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.auth.AccountSwitcher
import com.megamaced.nccollectives.data.auth.LoginFlowInitResponse
import com.megamaced.nccollectives.data.auth.LoginFlowStatus
import com.megamaced.nccollectives.data.auth.NextcloudLoginFlow
import com.megamaced.nccollectives.data.auth.isSameServerHttpsUrl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val hostInput: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val loginUrl: String? = null,
    val isPolling: Boolean = false,
    val loginSuccess: Boolean = false,
)

@HiltViewModel
class LoginViewModel
    @Inject
    constructor(
        private val loginFlow: NextcloudLoginFlow,
        private val accountSwitcher: AccountSwitcher,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(LoginUiState())
        val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

        fun onHostChanged(host: String) {
            _uiState.update { it.copy(hostInput = host, error = null) }
        }

        fun startLogin() {
            val host = _uiState.value.hostInput.trim()
            if (host.isBlank()) {
                _uiState.update { it.copy(error = "Enter your Nextcloud server URL") }
                return
            }

            // S-1: refuse `http://` outright. App-password Basic-auth over
            // cleartext is exfil bait on any shared network; the manifest's
            // `network_security_config.xml` also denies cleartext at the
            // platform level, but we surface a clear error here rather than
            // letting the underlying connection fail confusingly.
            if (host.startsWith("http://", ignoreCase = true)) {
                _uiState.update {
                    it.copy(error = "HTTPS is required — drop the http:// prefix.")
                }
                return
            }
            val normalisedHost = if (!host.startsWith("https://", ignoreCase = true)) {
                "https://$host"
            } else {
                host
            }

            _uiState.update { it.copy(isLoading = true, error = null) }

            viewModelScope.launch {
                // B-44: `loginFlow.initiate` is now `suspend` and owns its
                // own `Dispatchers.IO` switch + Response.use {}.
                val result = loginFlow.initiate(normalisedHost)
                result.fold(
                    onSuccess = { initResponse -> onFlowInitiated(initResponse, normalisedHost) },
                    onFailure = { e ->
                        _uiState.update {
                            it.copy(isLoading = false, error = e.message ?: "Connection failed")
                        }
                    },
                )
            }
        }

        private fun onFlowInitiated(
            initResponse: LoginFlowInitResponse,
            expectedHost: String,
        ) {
            // S-26: `login` goes to a Custom Tab and `poll.endpoint` is
            // POSTed to, both server-supplied and both used before `poll()`
            // gets to apply its own S-17 check to the returned `server`
            // field — by which point the user has already been shown a login
            // page and typed a password into it. Hold them to the same rule
            // as `server`, and fail before the tab opens.
            val serverSuppliedUrls = listOf(initResponse.login, initResponse.poll.endpoint)
            if (serverSuppliedUrls.any { !isSameServerHttpsUrl(it, expectedHost) }) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Server returned a different host than the one you entered " +
                            "($expectedHost). Refusing to continue.",
                    )
                }
                return
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    loginUrl = initResponse.login,
                    isPolling = true,
                )
            }

            viewModelScope.launch {
                // S-17: pass `expectedHost` so a server returning a different
                // canonical host than the user typed gets rejected before its
                // credentials are persisted.
                val status = loginFlow.poll(
                    endpoint = initResponse.poll.endpoint,
                    token = initResponse.poll.token,
                    expectedHost = expectedHost,
                )
                when (status) {
                    is LoginFlowStatus.Success -> {
                        // `signInTo` and not `SessionManager.onLoginSuccess`:
                        // this same screen is also the "add another account"
                        // flow (issue #14), and only the switcher knows
                        // whether there is an outgoing account's cache to
                        // wipe first. Deciding it there rather than from a
                        // mode flag is what keeps this screen mode-less.
                        accountSwitcher.signInTo(
                            host = status.result.server,
                            loginName = status.result.loginName,
                            appPassword = status.result.appPassword,
                        )
                        _uiState.update { it.copy(isPolling = false, loginSuccess = true) }
                    }

                    is LoginFlowStatus.Error -> {
                        _uiState.update {
                            it.copy(isPolling = false, error = status.message)
                        }
                    }

                    LoginFlowStatus.Polling -> {
                        // Unreachable: poll() only returns terminal states.
                    }
                }
            }
        }

        fun dismissError() {
            _uiState.update { it.copy(error = null) }
        }
    }
