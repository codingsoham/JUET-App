package com.example.juetapp.viewmodel

import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.juetapp.data.userData.AttendanceRecord
import com.example.juetapp.repository.WebkioskRepository
import kotlinx.coroutines.launch

class AttendanceViewModel(private val repository: WebkioskRepository) : ViewModel() {
    private val _uiState = mutableStateOf(AttendanceUiState())
    val uiState: State<AttendanceUiState> = _uiState

    init {
        loadAttendance()
    }

    fun loadAttendance() {
        viewModelScope.launch {
            _uiState.value = AttendanceUiState(isLoading = true)

            try {
                // First check if we have stored credentials
                val credentials = repository.getStoredCredentials()

                if (credentials == null) {
                    Log.e("AttendanceViewModel", "No stored credentials found")
                    _uiState.value = AttendanceUiState(
                        errorMessage = "Please login first",
                        isLoading = false
                    )
                    return@launch
                }

                Log.d("AttendanceViewModel", "Loaded credentials for: ${credentials.enrollmentNo}")

                // Try to get attendance data
                repository.getAttendance().fold(
                    onSuccess = { attendanceList ->
                        Log.d("AttendanceViewModel", "Successfully loaded ${attendanceList.size} attendance records")
                        _uiState.value = AttendanceUiState(
                            attendanceRecords = attendanceList,
                            isLoading = false
                        )
                    },
                    onFailure = { error ->
                        Log.e("AttendanceViewModel", "Failed to load attendance: ${error.message}", error)

                        // Check if it's a session timeout error
                        val errorMessage = when {
                            error.message?.contains("Session Timeout") == true -> {
                                "Session expired. Trying to refresh..."
                            }
                            error.message?.contains("Could not establish valid session") == true -> {
                                "Unable to connect to server. Please check your credentials and try again."
                            }
                            error.message?.contains("Invalid response") == true -> {
                                "Server returned invalid data. Please try again."
                            }
                            else -> error.message ?: "Failed to load attendance data"
                        }

                        _uiState.value = AttendanceUiState(
                            errorMessage = errorMessage,
                            isLoading = false
                        )

                        // If it's a session timeout, try to retry once
                        if (error.message?.contains("Session Timeout") == true) {
                            retryLoadAttendance()
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e("AttendanceViewModel", "Unexpected error in loadAttendance", e)
                _uiState.value = AttendanceUiState(
                    errorMessage = "Unexpected error occurred: ${e.message}",
                    isLoading = false
                )
            }
        }
    }

    private fun retryLoadAttendance() {
        viewModelScope.launch {
            Log.d("AttendanceViewModel", "Retrying attendance load after session timeout")

            // Add a small delay before retry
            kotlinx.coroutines.delay(2000)

            val credentials = repository.getStoredCredentials()
            if (credentials != null) {
                repository.getAttendance().fold(
                    onSuccess = { attendanceList ->
                        Log.d("AttendanceViewModel", "Retry successful: ${attendanceList.size} records")
                        _uiState.value = AttendanceUiState(
                            attendanceRecords = attendanceList,
                            isLoading = false
                        )
                    },
                    onFailure = { error ->
                        Log.e("AttendanceViewModel", "Retry also failed: ${error.message}")
                        _uiState.value = AttendanceUiState(
                            errorMessage = "Failed to load attendance after retry: ${error.message}",
                            isLoading = false
                        )
                    }
                )
            }
        }
    }

    fun refreshAttendance() {
        Log.d("AttendanceViewModel", "Manual refresh triggered")
        loadAttendance()
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}

data class AttendanceUiState(
    val attendanceRecords: List<AttendanceRecord> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class AttendanceViewModelFactory(private val repository: WebkioskRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AttendanceViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AttendanceViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}