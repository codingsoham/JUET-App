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

data class AttendanceRecord(
    val subject: String,
    val totalClasses: Int,
    val attendedClasses: Int,
    val percentage: Float
)

data class MarksRecord(
    val subject: String,
    val examType: String,
    val maxMarks: Int,
    val obtainedMarks: Int,
    val grade: String? = null
)