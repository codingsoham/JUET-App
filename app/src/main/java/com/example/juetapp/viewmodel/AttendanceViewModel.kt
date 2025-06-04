package com.example.juetapp.viewmodel

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

    // In AttendanceViewModel.kt
    fun loadAttendance() {
        viewModelScope.launch {
            _uiState.value = AttendanceUiState(isLoading = true)

            // Get stored credentials to refresh session if needed
            val credentials = repository.getStoredCredentials()

            repository.getAttendance(credentials).fold(
                onSuccess = { attendanceList ->
                    _uiState.value = AttendanceUiState(
                        attendanceRecords = attendanceList,
                        isLoading = false
                    )
                },
                onFailure = { error ->
                    _uiState.value = AttendanceUiState(
                        errorMessage = error.message ?: "Failed to load attendance",
                        isLoading = false
                    )
                }
            )
        }
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