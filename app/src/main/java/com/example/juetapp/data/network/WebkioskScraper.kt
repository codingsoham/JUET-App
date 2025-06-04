import android.util.Log
import com.example.juetapp.data.userData.AttendanceRecord
import com.example.juetapp.data.userData.MarksRecord
import com.example.juetapp.data.userData.UserCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.Calendar
import java.util.concurrent.TimeUnit

// Data classes for different sections
data class SubjectInfo(
    val subjectCode: String,
    val subjectName: String,
    val credits: Int,
    val semester: String
)

data class SubjectFaculty(
    val subjectCode: String,
    val subjectName: String,
    val facultyName: String,
    val semester: String
)

data class DisciplinaryAction(
    val date: String,
    val action: String,
    val reason: String,
    val status: String
)

data class SeatingPlan(
    val examDate: String,
    val examTime: String,
    val subject: String,
    val roomNo: String,
    val seatNo: String
)

data class CGPARecord(
    val semester: String,
    val sgpa: Float,
    val cgpa: Float,
    val totalCredits: Int
)

class WebkioskScraper {
    // Use a proper cookie jar implementation
    private val cookieJar = object : CookieJar {
        private val cookieStore = HashMap<String, List<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore[url.host] = cookies
            Log.d("WebkioskScraper", "Saved cookies: $cookies")
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val cookies = cookieStore[url.host] ?: emptyList()
            Log.d("WebkioskScraper", "Loading cookies for ${url.host}: $cookies")
            return cookies
        }
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    private val baseUrl = "https://webkiosk.juet.ac.in/"
    private val studentFilesUrl = "https://webkiosk.juet.ac.in/StudentFiles/"

    suspend fun login(credentials: UserCredentials): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                // First visit the homepage to get initial cookies
                val initialRequest = Request.Builder()
                    .url(baseUrl)
                    .build()

                val initialResponse = client.newCall(initialRequest).execute()
                val loginPageHtml = initialResponse.body?.string() ?: ""
                val document = Jsoup.parse(loginPageHtml)

                // Extract captcha
                val captcha = extractCaptcha(document)
                Log.d("WebkioskScraper", "Extracted Captcha: $captcha")

                // Submit login form
                val formData = FormBody.Builder()
                    .add("InstCode", "JUET")
                    .add("UserType", "S") // Default to Student type
                    .add("MemberCode", credentials.enrollmentNo)
                    .add("DATE1", credentials.dateOfBirth)
                    .add("Password", credentials.password)
                    .add("txtcap", captcha)
                    .add("BTNSubmit", "Submit")
                    .build()

                // Submit to the correct login endpoint
                val loginResponse = client.newCall(
                    Request.Builder()
                        .url("${baseUrl}StudentFiles/StudentPage.jsp")
                        .post(formData)
                        .build()
                ).execute()

                // Check if redirected to student page
                val responseUrl = loginResponse.request.url.toString()
                val responseBody = loginResponse.body?.string() ?: ""

                val isLoginSuccessful = !responseBody.contains("Invalid") &&
                        !responseBody.contains("Error") &&
                        !responseBody.contains("Session timeout") &&
                        (responseUrl.contains("StudentPage") || responseUrl.contains("DashBoard"))

                Log.d("WebkioskScraper", "Login response URL: $responseUrl")
                Log.d("WebkioskScraper", "Login successful: $isLoginSuccessful")

                Result.success(isLoginSuccessful)
            } catch (e: Exception) {
                Log.e("WebkioskScraper", "Login error", e)
                Result.failure(e)
            }
        }
    }

    // In WebkioskScraper.kt
// Add this method to refresh login session if needed
    suspend fun ensureActiveSession(credentials: UserCredentials): Boolean {
        val testRequest = Request.Builder()
            .url("${studentFilesUrl}Academic/StudentAttendanceList.jsp")
            .build()

        val response = client.newCall(testRequest).execute()
        val html = response.body?.string() ?: ""

        return if (html.contains("Session Timeout")) {
            // Session expired, login again
            val result = login(credentials)
            result.isSuccess && result.getOrDefault(false)
        } else {
            true // Session is still valid
        }
    }

    // Academic Info Methods
    suspend fun getAttendance(credentials: UserCredentials? = null): Result<List<AttendanceRecord>> {
        return withContext(Dispatchers.IO) {
            try {
                // If credentials provided, ensure active session first
                if (credentials != null) {
                    val sessionActive = ensureActiveSession(credentials)
                    if (!sessionActive) {
                        return@withContext Result.failure(Exception("Could not establish session"))
                    }
                }
                val request = Request.Builder()
                    .url("${studentFilesUrl}Academic/StudentAttendanceList.jsp")
                    .build()

                val response = client.newCall(request).execute()
                val html = response.body?.string() ?: ""

                Log.d("WebkioskScraper", "Attendance HTML response: ${html.take(500)}")

                val attendanceList = parseAttendanceData(html)
                Result.success(attendanceList)
            } catch (e: Exception) {
                Log.e("WebkioskScraper", "Attendance fetch error", e)
                Result.failure(e)
            }
        }
    }

    suspend fun getRegisteredSubjects(): Result<List<SubjectInfo>> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("${studentFilesUrl}Academic/StudSubjectTaken.jsp")
                    .build()

                val response = client.newCall(request).execute()
                val html = response.body?.string() ?: ""

                Log.d("WebkioskScraper", "Subject Registration HTML: ${html.take(500)}")

                val subjectList = parseSubjectData(html)
                Result.success(subjectList)
            } catch (e: Exception) {
                Log.e("WebkioskScraper", "Subject registration fetch error", e)
                Result.failure(e)
            }
        }
    }

    suspend fun getSubjectFaculty(): Result<List<SubjectFaculty>> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("${studentFilesUrl}Academic/StudSubjectFaculty.jsp")
                    .build()

                val response = client.newCall(request).execute()
                val html = response.body?.string() ?: ""

                Log.d("WebkioskScraper", "Subject Faculty HTML: ${html.take(500)}")

                val facultyList = parseSubjectFacultyData(html)
                Result.success(facultyList)
            } catch (e: Exception) {
                Log.e("WebkioskScraper", "Subject faculty fetch error", e)
                Result.failure(e)
            }
        }
    }

    suspend fun getDisciplinaryActions(): Result<List<DisciplinaryAction>> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("${studentFilesUrl}Academic/DisciplinaryAction.jsp")
                    .build()

                val response = client.newCall(request).execute()
                val html = response.body?.string() ?: ""

                Log.d("WebkioskScraper", "Disciplinary Action HTML: ${html.take(500)}")

                val actionList = parseDisciplinaryActionData(html)
                Result.success(actionList)
            } catch (e: Exception) {
                Log.e("WebkioskScraper", "Disciplinary action fetch error", e)
                Result.failure(e)
            }
        }
    }

    // Exam Info Methods
    suspend fun getSeatingPlan(): Result<List<SeatingPlan>> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("${studentFilesUrl}Exam/StudViewSeatPlan.jsp")
                    .build()

                val response = client.newCall(request).execute()
                val html = response.body?.string() ?: ""

                Log.d("WebkioskScraper", "Seating Plan HTML: ${html.take(500)}")

                val seatingPlanList = parseSeatingPlanData(html)
                Result.success(seatingPlanList)
            } catch (e: Exception) {
                Log.e("WebkioskScraper", "Seating plan fetch error", e)
                Result.failure(e)
            }
        }
    }

    suspend fun getExamMarks(): Result<List<MarksRecord>> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("${studentFilesUrl}Exam/StudentEventMarksView.jsp")
                    .build()

                val response = client.newCall(request).execute()
                val html = response.body?.string() ?: ""

                Log.d("WebkioskScraper", "Exam Marks HTML: ${html.take(500)}")

                val marksList = parseMarksData(html)
                Result.success(marksList)
            } catch (e: Exception) {
                Log.e("WebkioskScraper", "Exam marks fetch error", e)
                Result.failure(e)
            }
        }
    }

    suspend fun getCGPAReport(): Result<List<CGPARecord>> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("${studentFilesUrl}Exam/StudCGPAReport.jsp")
                    .build()

                val response = client.newCall(request).execute()
                val html = response.body?.string() ?: ""

                Log.d("WebkioskScraper", "CGPA Report HTML: ${html.take(500)}")

                val cgpaList = parseCGPAData(html)
                Result.success(cgpaList)
            } catch (e: Exception) {
                Log.e("WebkioskScraper", "CGPA report fetch error", e)
                Result.failure(e)
            }
        }
    }

    // Parsing Methods
    private fun extractCaptcha(document: Document): String {

        val captchaElement = document.selectFirst(".noselect")
        return captchaElement?.text()?.replace(Regex("[^A-Za-z0-9]"), "") ?: ""
    }

    private fun parseAttendanceData(html: String): List<AttendanceRecord> {
        val document = Jsoup.parse(html)
        val attendanceList = mutableListOf<AttendanceRecord>()

        // Find the main attendance table with class "sort-table"
        val attendanceTable = document.selectFirst("table.sort-table")
            ?: document.selectFirst("table#table-1")
            ?: return attendanceList

        // Skip header row and parse data rows
        attendanceTable.select("tbody tr").forEach { row ->
            val cells = row.select("td")
            if (cells.size >= 6) {
                try {
                    val sno = cells[0].text().trim()
                    val subject = cells[1].text().trim()

                    // Extract percentages from different columns
                    val lectureTutorialPercent = extractPercentageFromCell(cells[2])
                    val lecturePercent = extractPercentageFromCell(cells[3])
                    val tutorialPercent = extractPercentageFromCell(cells[4])
                    val practicalPercent = extractPercentageFromCell(cells[5])

                    if (subject.isNotEmpty() && sno.isNotEmpty()) {
                        // Create attendance record with the best available percentage
                        val overallPercentage = when {
                            lectureTutorialPercent > 0 -> lectureTutorialPercent
                            lecturePercent > 0 -> lecturePercent
                            practicalPercent > 0 -> practicalPercent
                            tutorialPercent > 0 -> tutorialPercent
                            else -> 0f
                        }

                        attendanceList.add(
                            AttendanceRecord(
                                subject = subject,
                                totalClasses = 0, // Not provided in this format
                                attendedClasses = 0, // Not provided in this format
                                percentage = overallPercentage,
                                lecturePercent = if (lecturePercent > 0) lecturePercent else null,
                                tutorialPercent = if (tutorialPercent > 0) tutorialPercent else null,
                                practicalPercent = if (practicalPercent > 0) practicalPercent else null,
                                lectureTutorialPercent = if (lectureTutorialPercent > 0) lectureTutorialPercent else null
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.w("WebkioskScraper", "Error parsing attendance row: ${e.message}")
                }
            }
        }

        return attendanceList
    }

    private fun extractPercentageFromCell(cell: org.jsoup.nodes.Element): Float {
        // Extract percentage from link text or cell text
        val linkElement = cell.selectFirst("a")
        val text = if (linkElement != null) {
            linkElement.text().trim()
        } else {
            cell.text().trim()
        }

        return if (text.isNotEmpty() && text != "&nbsp;" && text != " ") {
            text.replace("%", "").toFloatOrNull() ?: 0f
        } else {
            0f
        }
    }

    private fun parseSubjectData(html: String): List<SubjectInfo> {
        val document = Jsoup.parse(html)
        val subjectList = mutableListOf<SubjectInfo>()

        document.select("table").forEach { table ->
            table.select("tr").drop(1).forEach { row ->
                val cells = row.select("td")
                if (cells.size >= 4) {
                    try {
                        val subjectCode = cells[0].text().trim()
                        val subjectName = cells[1].text().trim()
                        val credits = cells[2].text().trim().toIntOrNull() ?: 0
                        val semester = cells[3].text().trim()

                        if (subjectCode.isNotEmpty()) {
                            subjectList.add(
                                SubjectInfo(subjectCode, subjectName, credits, semester)
                            )
                        }
                    } catch (e: Exception) {
                        Log.w("WebkioskScraper", "Error parsing subject row: ${e.message}")
                    }
                }
            }
        }

        return subjectList
    }

    private fun parseSubjectFacultyData(html: String): List<SubjectFaculty> {
        val document = Jsoup.parse(html)
        val facultyList = mutableListOf<SubjectFaculty>()

        document.select("table").forEach { table ->
            table.select("tr").drop(1).forEach { row ->
                val cells = row.select("td")
                if (cells.size >= 4) {
                    try {
                        val subjectCode = cells[0].text().trim()
                        val subjectName = cells[1].text().trim()
                        val facultyName = cells[2].text().trim()
                        val semester = cells[3].text().trim()

                        if (subjectCode.isNotEmpty()) {
                            facultyList.add(
                                SubjectFaculty(subjectCode, subjectName, facultyName, semester)
                            )
                        }
                    } catch (e: Exception) {
                        Log.w("WebkioskScraper", "Error parsing faculty row: ${e.message}")
                    }
                }
            }
        }

        return facultyList
    }

    private fun parseDisciplinaryActionData(html: String): List<DisciplinaryAction> {
        val document = Jsoup.parse(html)
        val actionList = mutableListOf<DisciplinaryAction>()

        document.select("table").forEach { table ->
            table.select("tr").drop(1).forEach { row ->
                val cells = row.select("td")
                if (cells.size >= 4) {
                    try {
                        val date = cells[0].text().trim()
                        val action = cells[1].text().trim()
                        val reason = cells[2].text().trim()
                        val status = cells[3].text().trim()

                        actionList.add(
                            DisciplinaryAction(date, action, reason, status)
                        )
                    } catch (e: Exception) {
                        Log.w("WebkioskScraper", "Error parsing disciplinary action row: ${e.message}")
                    }
                }
            }
        }

        return actionList
    }

    private fun parseSeatingPlanData(html: String): List<SeatingPlan> {
        val document = Jsoup.parse(html)
        val seatingPlanList = mutableListOf<SeatingPlan>()

        document.select("table").forEach { table ->
            table.select("tr").drop(1).forEach { row ->
                val cells = row.select("td")
                if (cells.size >= 5) {
                    try {
                        val examDate = cells[0].text().trim()
                        val examTime = cells[1].text().trim()
                        val subject = cells[2].text().trim()
                        val roomNo = cells[3].text().trim()
                        val seatNo = cells[4].text().trim()

                        seatingPlanList.add(
                            SeatingPlan(examDate, examTime, subject, roomNo, seatNo)
                        )
                    } catch (e: Exception) {
                        Log.w("WebkioskScraper", "Error parsing seating plan row: ${e.message}")
                    }
                }
            }
        }

        return seatingPlanList
    }

    private fun parseMarksData(html: String): List<MarksRecord> {
        val document = Jsoup.parse(html)
        val marksList = mutableListOf<MarksRecord>()

        document.select("table").forEach { table ->
            table.select("tr").drop(1).forEach { row ->
                val cells = row.select("td")
                if (cells.size >= 5) {
                    try {
                        val subject = cells[0].text().trim()
                        val examType = cells[1].text().trim()
                        val maxMarks = cells[2].text().trim().toIntOrNull() ?: 0
                        val obtainedMarks = cells[3].text().trim().toIntOrNull() ?: 0
                        val grade = cells[4].text().trim()

                        if (subject.isNotEmpty()) {
                            marksList.add(
                                MarksRecord(subject, examType, maxMarks, obtainedMarks, grade)
                            )
                        }
                    } catch (e: Exception) {
                        Log.w("WebkioskScraper", "Error parsing marks row: ${e.message}")
                    }
                }
            }
        }

        return marksList
    }

    private fun parseCGPAData(html: String): List<CGPARecord> {
        val document = Jsoup.parse(html)
        val cgpaList = mutableListOf<CGPARecord>()

        document.select("table").forEach { table ->
            table.select("tr").drop(1).forEach { row ->
                val cells = row.select("td")
                if (cells.size >= 4) {
                    try {
                        val semester = cells[0].text().trim()
                        val sgpa = cells[1].text().trim().toFloatOrNull() ?: 0f
                        val cgpa = cells[2].text().trim().toFloatOrNull() ?: 0f
                        val totalCredits = cells[3].text().trim().toIntOrNull() ?: 0

                        if (semester.isNotEmpty()) {
                            cgpaList.add(
                                CGPARecord(semester, sgpa, cgpa, totalCredits)
                            )
                        }
                    } catch (e: Exception) {
                        Log.w("WebkioskScraper", "Error parsing CGPA row: ${e.message}")
                    }
                }
            }
        }

        return cgpaList
    }

    private fun getCurrentDateTime(): String {
        val calendar = Calendar.getInstance()
        return "${calendar.get(Calendar.DAY_OF_MONTH)}" +
                "${calendar.get(Calendar.MONTH) + 1}" +
                "${calendar.get(Calendar.YEAR)}" +
                "${calendar.get(Calendar.HOUR_OF_DAY)}" +
                "${calendar.get(Calendar.MINUTE)}" +
                "${calendar.get(Calendar.SECOND)}"
    }
}