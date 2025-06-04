package com.example.juetapp.repository

import CGPARecord
import DisciplinaryAction
import SeatingPlan
import SubjectFaculty
import SubjectInfo
import WebkioskScraper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.juetapp.data.userData.AttendanceRecord
import com.example.juetapp.data.userData.MarksRecord
import com.example.juetapp.data.userData.UserCredentials
import kotlinx.coroutines.flow.first

class WebkioskRepository(
    private val scraper: WebkioskScraper,
    private val dataStore: DataStore<Preferences>
) {
    private val ENROLLMENT_KEY = stringPreferencesKey("enrollment_no")
    private val DOB_KEY = stringPreferencesKey("date_of_birth")
    private val PASSWORD_KEY = stringPreferencesKey("password")

    suspend fun saveCredentials(credentials: UserCredentials) {
        dataStore.edit { preferences ->
            preferences[ENROLLMENT_KEY] = credentials.enrollmentNo
            preferences[DOB_KEY] = credentials.dateOfBirth
            preferences[PASSWORD_KEY] = credentials.password
        }
    }

    suspend fun getStoredCredentials(): UserCredentials? {
        val preferences = dataStore.data.first()
        val enrollment = preferences[ENROLLMENT_KEY]
        val dob = preferences[DOB_KEY]
        val password = preferences[PASSWORD_KEY]

        return if (enrollment != null && dob != null && password != null) {
            UserCredentials(enrollment, dob, password)
        } else null
    }

    suspend fun login(credentials: UserCredentials): Result<Boolean> {
        val result = scraper.login(credentials)
        if (result.isSuccess && result.getOrNull() == true) {
            saveCredentials(credentials)
        }
        return result
    }

    // Academic Information Methods
    suspend fun getAttendance(credentials: UserCredentials?): Result<List<AttendanceRecord>> {
        return scraper.getAttendance()
    }

    suspend fun getRegisteredSubjects(): Result<List<SubjectInfo>> {
        return scraper.getRegisteredSubjects()
    }

    suspend fun getSubjectFaculty(): Result<List<SubjectFaculty>> {
        return scraper.getSubjectFaculty()
    }

    suspend fun getDisciplinaryActions(): Result<List<DisciplinaryAction>> {
        return scraper.getDisciplinaryActions()
    }

    // Exam Information Methods
    suspend fun getSeatingPlan(): Result<List<SeatingPlan>> {
        return scraper.getSeatingPlan()
    }

    suspend fun getExamMarks(): Result<List<MarksRecord>> {
        return scraper.getExamMarks()
    }

    suspend fun getCGPAReport(): Result<List<CGPARecord>> {
        return scraper.getCGPAReport()
    }

    // Convenience method to fetch all academic data at once
    suspend fun getAllAcademicData(credentials: UserCredentials): AcademicDataResult {
        return AcademicDataResult(
            attendance = getAttendance(credentials),
            subjects = getRegisteredSubjects(),
            faculty = getSubjectFaculty(),
            disciplinaryActions = getDisciplinaryActions()
        )
    }

    // Convenience method to fetch all exam data at once
    suspend fun getAllExamData(): ExamDataResult {
        return ExamDataResult(
            seatingPlan = getSeatingPlan(),
            marks = getExamMarks(),
            cgpa = getCGPAReport()
        )
    }
}

// Data classes for batch operations
data class AcademicDataResult(
    val attendance: Result<List<AttendanceRecord>>,
    val subjects: Result<List<SubjectInfo>>,
    val faculty: Result<List<SubjectFaculty>>,
    val disciplinaryActions: Result<List<DisciplinaryAction>>
)

data class ExamDataResult(
    val seatingPlan: Result<List<SeatingPlan>>,
    val marks: Result<List<MarksRecord>>,
    val cgpa: Result<List<CGPARecord>>
)