package com.megamaced.nccollectives.ui.screen.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.megamaced.nccollectives.data.auth.LoginFlowInitResponse
import com.megamaced.nccollectives.data.auth.LoginFlowStatus
import com.megamaced.nccollectives.data.auth.NextcloudLoginFlow
import com.megamaced.nccollectives.data.auth.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        private val sessionManager: SessionManager,
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

            val normalisedHost = if (!host.startsWith("http")) "https://$host" else host

            _uiState.update { it.copy(isLoading = true, error = null) }

            viewModelScope.launch {
                val result = withContext(Dispatchers.IO) {
                    loginFlow.initiate(normalisedHost)
                }
                result.fold(
                    onSuccess = { initResponse -> onFlowInitiated(initResponse) },
                    onFailure = { e ->
                        _uiState.update {
                            it.copy(isLoading = false, error = e.message ?: "Connection failed")
                        }
                    },
                )
            }
        }

        private fun onFlowInitiated(initResponse: LoginFlowInitResponse) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    loginUrl = initResponse.login,
                    isPolling = true,
                )
            }

            viewModelScope.launch {
                val status = withContext(Dispatchers.IO) {
                    loginFlow.poll(initResponse.poll.endpoint, initResponse.poll.token)
                }
                when (status) {
                    is LoginFlowStatus.Success -> {
                        sessionManager.onLoginSuccess(
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
