package com.example.juetapp.repository

import WebkioskScraper
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.juetapp.data.userData.AttendanceRecord
import com.example.juetapp.data.userData.UserCredentials
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WebkioskRepository(
    private val scraper: WebkioskScraper,
    private val dataStore: DataStore<Preferences>
) {
    private val ENROLLMENT_KEY = stringPreferencesKey("enrollment_no")
    private val DOB_KEY = stringPreferencesKey("date_of_birth")
    private val PASSWORD_KEY = stringPreferencesKey("password")
    private val USER_TYPE_KEY = stringPreferencesKey("user_type")

    // Mutex to prevent concurrent login attempts
    private val loginMutex = Mutex()

    // Track login state to avoid unnecessary re-logins
    private var lastLoginTime = 0L
    private var lastLoginSuccess = false
    private val LOGIN_CACHE_DURATION = 5 * 60 * 1000L // 5 minutes

    suspend fun saveCredentials(credentials: UserCredentials) {
        dataStore.edit { preferences ->
            preferences[ENROLLMENT_KEY] = credentials.enrollmentNo
            preferences[DOB_KEY] = credentials.dateOfBirth
            preferences[PASSWORD_KEY] = credentials.password
            preferences[USER_TYPE_KEY] = credentials.userType
        }
        Log.d("WebkioskRepository", "Credentials saved for: ${credentials.enrollmentNo}")
    }

    suspend fun getStoredCredentials(): UserCredentials? {
        return try {
            val preferences = dataStore.data.first()
            val enrollment = preferences[ENROLLMENT_KEY]
            val dob = preferences[DOB_KEY]
            val password = preferences[PASSWORD_KEY]
            val userType = preferences[USER_TYPE_KEY] ?: "Student" // Default to Student

            if (enrollment != null && dob != null && password != null) {
                UserCredentials(
                    enrollmentNo = enrollment,
                    dateOfBirth = dob,
                    password = password,
                    userType = userType
                )
            } else {
                Log.w("WebkioskRepository", "Incomplete credentials stored")
                null
            }
        } catch (e: Exception) {
            Log.e("WebkioskRepository", "Error retrieving credentials", e)
            null
        }
    }

    suspend fun login(credentials: UserCredentials): Result<Boolean> {
        return loginMutex.withLock {
            try {
                Log.d("WebkioskRepository", "Attempting login for: ${credentials.enrollmentNo}")

                val result = scraper.login(credentials)

                if (result.isSuccess && result.getOrNull() == true) {
                    Log.d("WebkioskRepository", "Login successful, saving credentials")
                    saveCredentials(credentials)
                    lastLoginTime = System.currentTimeMillis()
                    lastLoginSuccess = true
                } else {
                    Log.w("WebkioskRepository", "Login failed")
                    lastLoginSuccess = false
                }

                return@withLock result
            } catch (e: Exception) {
                Log.e("WebkioskRepository", "Login error", e)
                lastLoginSuccess = false
                return@withLock Result.failure(e)
            }
        }
    }

    // Enhanced session management method
// Enhanced session management with better retry logic
    private suspend fun ensureActiveSession(): Result<UserCredentials> {
        return loginMutex.withLock {
            try {
                val credentials = getStoredCredentials()
                    ?: return@withLock Result.failure(Exception("No stored credentials found"))

                Log.d("WebkioskRepository", "Checking session for: ${credentials.enrollmentNo}")

                // Always attempt fresh login for attendance requests to avoid session issues
                // Remove the cache check temporarily to ensure we always have a fresh session
                Log.d("WebkioskRepository", "Attempting fresh login for attendance request")
                val loginResult = scraper.login(credentials)

                if (loginResult.isSuccess && loginResult.getOrNull() == true) {
                    Log.d("WebkioskRepository", "Fresh login successful")
                    lastLoginTime = System.currentTimeMillis()
                    lastLoginSuccess = true

                    // Add delay after successful login
                    kotlinx.coroutines.delay(1000)

                    return@withLock Result.success(credentials)
                } else {
                    Log.e("WebkioskRepository", "Fresh login failed")
                    lastLoginSuccess = false
                    return@withLock Result.failure(Exception("Failed to establish session"))
                }

            } catch (e: Exception) {
                Log.e("WebkioskRepository", "Session management error", e)
                lastLoginSuccess = false
                return@withLock Result.failure(e)
            }
        }
    }

    // Improved attendance method with single retry
    suspend fun getAttendance(providedCredentials: UserCredentials? = null): Result<List<AttendanceRecord>> {
        return try {
            Log.d("WebkioskRepository", "=== Getting Attendance ===")

            val credentials = if (providedCredentials != null) {
                Log.d("WebkioskRepository", "Using provided credentials")
                providedCredentials
            } else {
                Log.d("WebkioskRepository", "Ensuring active session")
                val sessionResult = ensureActiveSession()
                if (sessionResult.isFailure) {
                    return Result.failure(sessionResult.exceptionOrNull() ?: Exception("Session establishment failed"))
                }
                sessionResult.getOrNull()!!
            }

            // Single attempt to get attendance with the established session
            Log.d("WebkioskRepository", "Fetching attendance data")
            val attendanceResult = scraper.getAttendanceFromFrameset(credentials)

            if (attendanceResult.isSuccess) {
                val records = attendanceResult.getOrNull() ?: emptyList()
                Log.d("WebkioskRepository", "Successfully retrieved ${records.size} attendance records")
                return Result.success(records)
            } else {
                val error = attendanceResult.exceptionOrNull()
                Log.e("WebkioskRepository", "Attendance fetch failed: ${error?.message}")
                return attendanceResult
            }

        } catch (e: Exception) {
            Log.e("WebkioskRepository", "Unexpected error in getAttendance", e)
            Result.failure(e)
        }
    }

    // Method to clear stored credentials (useful for logout)
    suspend fun clearCredentials() {
        try {
            dataStore.edit { preferences ->
                preferences.clear()
            }
            lastLoginSuccess = false
            lastLoginTime = 0L
            Log.d("WebkioskRepository", "Credentials cleared")
        } catch (e: Exception) {
            Log.e("WebkioskRepository", "Error clearing credentials", e)
        }
    }

    // Method to check if we have stored credentials
    suspend fun hasStoredCredentials(): Boolean {
        return getStoredCredentials() != null
    }
}