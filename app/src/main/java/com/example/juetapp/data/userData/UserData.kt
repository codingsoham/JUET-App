package com.example.juetapp.data.userData

data class UserCredentials(
    val enrollmentNo: String,
    val dateOfBirth: String,
    val password: String,
    val userType: String = "S" // S for Student
)

data class StudentInfo(
    val name: String,
    val enrollmentNo: String,
    val course: String,
    val semester: String,
    val profileImageUrl: String? = null
)

// Updated AttendanceRecord data class to match the webkiosk structure
data class AttendanceRecord(
    val subject: String,
    val totalClasses: Int = 0, // Not available in webkiosk format
    val attendedClasses: Int = 0, // Not available in webkiosk format
    val percentage: Float, // Overall percentage (best available)
    val lecturePercent: Float? = null,
    val tutorialPercent: Float? = null,
    val practicalPercent: Float? = null,
    val lectureTutorialPercent: Float? = null
) {
    // Helper function to get the subject code from subject name
    fun getSubjectCode(): String {
        val regex = Regex("""[A-Z]{2}\d{3}""")
        return regex.find(subject)?.value ?: ""
    }

    // Helper function to get clean subject name without code
    fun getCleanSubjectName(): String {
        val regex = Regex("""\s*-\s*[A-Z]{2}\d{3}$""")
        return subject.replace(regex, "").trim()
    }

    // Helper function to determine attendance status
    fun getAttendanceStatus(): AttendanceStatus {
        return when {
            percentage >= 85 -> AttendanceStatus.EXCELLENT
            percentage >= 75 -> AttendanceStatus.GOOD
            percentage >= 65 -> AttendanceStatus.AVERAGE
            percentage >= 50 -> AttendanceStatus.LOW
            else -> AttendanceStatus.CRITICAL
        }
    }
}

enum class AttendanceStatus(val color: String, val text: String) {
    EXCELLENT("#4CAF50", "Excellent"),
    GOOD("#8BC34A", "Good"),
    AVERAGE("#FF9800", "Average"),
    LOW("#FF5722", "Low"),
    CRITICAL("#F44336", "Critical")
}

data class MarksRecord(
    val subject: String,
    val examType: String,
    val maxMarks: Int,
    val obtainedMarks: Int,
    val grade: String? = null
)