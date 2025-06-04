package com.example.juetapp.viewmodel

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.juetapp.data.userData.UserCredentials
import com.example.juetapp.repository.WebkioskRepository
import kotlinx.coroutines.launch

class LoginViewModel(
    private val repository: WebkioskRepository
) : ViewModel() {

    private val _uiState = mutableStateOf(LoginUiState())
    val uiState: State<LoginUiState> = _uiState

    init {
        loadStoredCredentials()
    }

    fun updateEnrollmentNo(value: String) {
        _uiState.value = _uiState.value.copy(enrollmentNo = value)
    }

    fun updateDateOfBirth(value: String) {
        _uiState.value = _uiState.value.copy(dateOfBirth = value)
    }

    fun updatePassword(value: String) {
        _uiState.value = _uiState.value.copy(password = value)
    }

    fun login() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            val credentials = UserCredentials(
                enrollmentNo = _uiState.value.enrollmentNo,
                dateOfBirth = _uiState.value.dateOfBirth,
                password = _uiState.value.password
            )

            repository.login(credentials).fold(
                onSuccess = { success ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isLoggedIn = success,
                        errorMessage = if (!success) "Invalid credentials" else null
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Login failed"
                    )
                }
            )
        }
    }

    private fun loadStoredCredentials() {
        viewModelScope.launch {
            repository.getStoredCredentials()?.let { credentials ->
                _uiState.value = _uiState.value.copy(
                    enrollmentNo = credentials.enrollmentNo,
                    dateOfBirth = credentials.dateOfBirth,
                    password = credentials.password
                )
            }
        }
    }
}

data class LoginUiState(
    val enrollmentNo: String = "",
    val dateOfBirth: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val errorMessage: String? = null
)