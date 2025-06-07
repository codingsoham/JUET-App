import android.util.Log
import com.example.juetapp.data.userData.AttendanceRecord
import com.example.juetapp.data.userData.UserCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

class WebkioskScraper {
    private val baseUrl = "https://webkiosk.juet.ac.in"
    private val loginUrl = "$baseUrl/index.jsp"
    private val studentFilesUrl = "$baseUrl/StudentFiles"

    // Improved cookie jar with better domain handling
    private val cookieJar = object : CookieJar {
        private val cookieStore = HashMap<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isNotEmpty()) {
                Log.d("WebkioskScraper", "Saving cookies for ${url.host}: ${cookies.size} cookies")

                // Store cookies for the exact host
                val hostKey = url.host
                cookieStore[hostKey] = cookies.toMutableList()

                // Log important cookies
                cookies.forEach { cookie ->
                    Log.d("WebkioskScraper", "Cookie: ${cookie.name}=${cookie.value}, Domain: ${cookie.domain}, Path: ${cookie.path}")
                    if (cookie.name == "JSESSIONID") {
                        Log.d("WebkioskScraper", "JSESSIONID saved: ${cookie.value}")
                    }
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val hostKey = url.host
            val cookies = cookieStore[hostKey] ?: mutableListOf()

            // Filter cookies that are valid for this request
            val validCookies = cookies.filter { cookie ->
                // Check if cookie domain matches
                val cookieDomain = cookie.domain
                val hostMatches = when {
                    cookieDomain.startsWith(".") -> url.host.endsWith(cookieDomain.substring(1))
                    else -> url.host == cookieDomain || url.host.endsWith(".$cookieDomain")
                }

                // Check if cookie path matches
                val pathMatches = url.encodedPath.startsWith(cookie.path)

                // Check if cookie is not expired
                val notExpired = cookie.expiresAt > System.currentTimeMillis()

                hostMatches && pathMatches && (cookie.persistent || notExpired)
            }

            Log.d("WebkioskScraper", "Loading ${validCookies.size} valid cookies for ${url.host}${url.encodedPath}")
            validCookies.forEach { cookie ->
                if (cookie.name == "JSESSIONID") {
                    Log.d("WebkioskScraper", "JSESSIONID loaded: ${cookie.value}")
                }
            }

            return validCookies
        }

        private fun extractBaseDomain(host: String): String {
            val parts = host.split('.')
            return if (parts.size >= 2) {
                parts.takeLast(2).joinToString(".")
            } else host
        }

        fun hasSessionCookie(): Boolean {
            return cookieStore.values.flatten().any {
                it.name == "JSESSIONID" && it.value.isNotBlank() &&
                        it.expiresAt > System.currentTimeMillis()
            }
        }

        fun clearCookies() {
            cookieStore.clear()
            Log.d("WebkioskScraper", "All cookies cleared")
        }
    }

    // HTTP client with better configuration
    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("Connection", "keep-alive")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "same-origin")
                .build()
            chain.proceed(request)
        }
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.HEADERS // Reduced logging to avoid clutter
        })
        .build()

    // Improved login method with better error handling
    suspend fun login(credentials: UserCredentials): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d("WebkioskScraper", "=== Starting Login Process ===")

                // Clear any existing cookies first
                cookieJar.clearCookies()

                // Step 1: Get initial page to establish session
                Log.d("WebkioskScraper", "Step 1: Getting initial login page")
                val initialRequest = Request.Builder()
                    .url(loginUrl)
                    .get()
                    .build()

                Log.d("WebkioskScraper", "Executing initial request to ${initialRequest.url}")
                Log.d("WebkioskScraper", "Initial request headers: ${initialRequest}")
                val initialResponse = client.newCall(initialRequest).execute()
                val initialHtml = initialResponse.body?.string() ?: ""
                Log.d("WebkioskScraper", "Initial response: ${initialHtml}")
                Log.d("WebkioskScraper", "Initial response code: ${initialResponse.code}")
                Log.d("WebkioskScraper", "Initial response length: ${initialHtml.length}")

                if (initialResponse.code != 200) {
                    return@withContext Result.failure(Exception("Failed to load login page: ${initialResponse.code}"))
                }

                // Step 2: Extract captcha
                Log.d("WebkioskScraper", "Step 2: Extracting captcha")
                val captcha = extractCaptcha(initialHtml)
                if (captcha == null) {
                    Log.e("WebkioskScraper", "Failed to extract captcha from HTML")
                    return@withContext Result.failure(Exception("Failed to extract captcha"))
                }

                Log.d("WebkioskScraper", "Extracted captcha: '$captcha'")

                // Step 3: Submit login form
                Log.d("WebkioskScraper", "Step 3: Submitting login form")
                val formBody = FormBody.Builder()
                    .add("InstCode", "JUET")  // Note: case-sensitive
                    .add("UserType", credentials.userType)
                    .add("MemberCode", credentials.enrollmentNo)
                    .add("DATE1", credentials.dateOfBirth)
                    .add("Password", credentials.password)
                    .add("txtcap", captcha)
                    .add("BTNSubmit", "Submit")
                    .add("x", "")  // Add the hidden field with empty value
                    .build()

// Use the correct form action URL from the HTML
                val loginUrl = "$baseUrl/CommonFiles/UserAction.jsp"

                val loginRequest = Request.Builder()
                    .url(loginUrl)  // Correct endpoint
                    .post(formBody)
                    .header("Referer", baseUrl)
                    .header("Origin", baseUrl)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .build()

                Log.d("WebkioskScraper", "Executing login request to ${loginRequest.url}")
                Log.d("WebkioskScraper", "Login request body: ${loginRequest}")
                val loginResponse = client.newCall(loginRequest).execute()
                val responseBody = loginResponse.body?.string() ?: ""
                Log.d("WebkioskScraper", "Login response body: $responseBody")
                Log.d("WebkioskScraper", "Login response code: ${loginResponse.code}")
                Log.d("WebkioskScraper", "Login response URL: ${loginResponse.request.url}")
                Log.d("WebkioskScraper", "Response body length: ${responseBody.length}")

                // Step 4: Check login success
                val loginSuccess = checkLoginSuccess(responseBody, loginResponse)

                if (loginSuccess) {
                    Log.d("WebkioskScraper", "=== Login Successful ===")
                    return@withContext Result.success(true)
                } else {
                    Log.e("WebkioskScraper", "=== Login Failed ===")
                    return@withContext Result.success(false)
                }

            } catch (e: Exception) {
                Log.e("WebkioskScraper", "Login error", e)
                return@withContext Result.failure(e)
            }
        }
    }

    // Updated login success check that handles the empty response with verification
    private fun checkLoginSuccess(responseBody: String, loginResponse: okhttp3.Response): Boolean {
        Log.d("WebkioskScraper", "=== Checking Login Success ===")
        Log.d("WebkioskScraper", "Response body content: '${responseBody}'")

        // Check 1: HTTP status
        val statusOk = loginResponse.code == 200
        Log.d("WebkioskScraper", "Status OK: $statusOk (${loginResponse.code})")

        // Check 2: Has session cookie
        val hasSessionCookie = cookieJar.hasSessionCookie()
        Log.d("WebkioskScraper", "Has session cookie: $hasSessionCookie")

        // Check 3: URL indicates success
        val correctUrl = loginResponse.request.url.toString().contains("StudentPage.jsp")
        Log.d("WebkioskScraper", "Correct URL: $correctUrl")

        // Empty response is normal for this system, so we should check if we need to verify
        val isEmpty = responseBody.trim().isEmpty() || responseBody.length <= 10
        Log.d("WebkioskScraper", "Response is empty or minimal: $isEmpty")

        // If we have an empty response but all other indicators are good, perform verification
        if (statusOk && hasSessionCookie && correctUrl && isEmpty) {
            Log.d("WebkioskScraper", "Empty response but good indicators - performing verification")
            return verifyLoginSuccess()
        }

        // For non-empty responses, check for traditional indicators
        val noErrorIndicators = !responseBody.contains("Invalid", ignoreCase = true) &&
                !responseBody.contains("Error", ignoreCase = true) &&
                !responseBody.contains("incorrect", ignoreCase = true) &&
                !responseBody.contains("failed", ignoreCase = true)

        val hasFrameset = responseBody.contains("<frameset", ignoreCase = true)

        Log.d("WebkioskScraper", "No error indicators: $noErrorIndicators")
        Log.d("WebkioskScraper", "Has frameset: $hasFrameset")

        val isSuccess = statusOk && hasSessionCookie && correctUrl &&
                (isEmpty || (noErrorIndicators && hasFrameset))

        Log.d("WebkioskScraper", "Overall success: $isSuccess")

        return isSuccess
    }

    // Add this method to verify login success by making a follow-up request
    private fun verifyLoginSuccess(): Boolean {
        try {
            // Attempt to access the frameset page to verify login
            val verificationRequest = Request.Builder()
                .url("$studentFilesUrl/FrameLeftStudent.jsp")
                .get()
                .build()

            val verificationResponse = client.newCall(verificationRequest).execute()
            val verificationBody = verificationResponse.body?.string() ?: ""

            Log.d("WebkioskScraper", "Verification response code: ${verificationResponse.code}")
            Log.d("WebkioskScraper", "Verification body length: ${verificationBody.length}")

            // Check for successful indicators in the frameset
            val isSuccess = verificationResponse.code == 200 &&
                    verificationBody.length > 100 &&
                    (verificationBody.contains("Student", ignoreCase = true) ||
                            verificationBody.contains("JUET", ignoreCase = true))

            Log.d("WebkioskScraper", "Verification success: $isSuccess")
            return isSuccess

        } catch (e: Exception) {
            Log.e("WebkioskScraper", "Error during login verification", e)
            return false
        }
    }

    // Improved captcha extraction
    private fun extractCaptcha(html: String): String? {
        try {
            val doc = Jsoup.parse(html)

            // Method 1: Try the previously working approach with .noselect class
            val captchaElement = doc.selectFirst(".noselect")
            val noSelectText = captchaElement?.text()?.replace(Regex("[^A-Za-z0-9]"), "")
            if (!noSelectText.isNullOrBlank() && noSelectText.length in 4..6) {
                Log.d("WebkioskScraper", "Found captcha using .noselect: '$noSelectText'")
                return noSelectText
            }

            // Method 2: Look for specific table cell that contains captcha (common in Webkiosk)
            val captchaCells = doc.select("table td:contains(Enter Captcha):not(:contains(Jaypee))")
            val nextCell = captchaCells.firstOrNull()?.nextElementSibling()
            val cellText = nextCell?.text()?.replace(Regex("[^A-Za-z0-9]"), "")
            if (!cellText.isNullOrBlank() && cellText.length in 4..6) {
                Log.d("WebkioskScraper", "Found captcha in table cell: '$cellText'")
                return cellText
            }

            // Method 3: Look for a short text that's likely a captcha (not "Jaypee")
            val potentialCaptchas = doc.select("font[face=Arial][color=blue], .loginCaptcha, #captcha, #cap")
            for (element in potentialCaptchas) {
                val text = element.text().replace(Regex("[^A-Za-z0-9]"), "")
                if (text.length in 4..6 && text != "Jaypee") {
                    Log.d("WebkioskScraper", "Found captcha in element: '$text'")
                    return text
                }
            }

            // Method 4: Search for captcha in image alt text
            val captchaImages = doc.select("img[src*=captcha], img[alt*=captcha]")
            for (img in captchaImages) {
                val altText = img.attr("alt").replace(Regex("[^A-Za-z0-9]"), "")
                if (altText.length in 4..6) {
                    Log.d("WebkioskScraper", "Found captcha in image alt: '$altText'")
                    return altText
                }
            }

            // Debug: Log the HTML structure to identify captcha location
            Log.d("WebkioskScraper", "Could not find captcha, logging HTML structure for debugging")
            val htmlStructure = doc.select("body > *").joinToString("\n") { it.tagName() + ": " + it.className() }
            Log.d("WebkioskScraper", "HTML structure: $htmlStructure")

            return null
        } catch (e: Exception) {
            Log.e("WebkioskScraper", "Error extracting captcha", e)
            return null
        }
    }
    
    // Helper method to extract attendance links from left frame
    private fun extractAttendanceLinksFromLeftFrame(html: String): List<String> {
        val links = mutableListOf<String>()
        try {
            val doc = Jsoup.parse(html)

            // Look for links containing attendance-related keywords
            val attendanceKeywords = listOf("attendance", "Attendance", "ATTENDANCE")

            for (keyword in attendanceKeywords) {
                val elements = doc.select("a[href*=$keyword]")
                elements.forEach { element ->
                    val href = element.attr("href")
                    if (href.isNotBlank()) {
                        links.add(href)
                        Log.d("WebkioskScraper", "Found attendance link: $href")
                    }
                }
            }

            // Also look for any links in Academic section
            val academicLinks = doc.select("a[href*=Academic]")
            academicLinks.forEach { element ->
                val href = element.attr("href")
                if (href.isNotBlank()) {
                    links.add(href)
                    Log.d("WebkioskScraper", "Found academic link: $href")
                }
            }

        } catch (e: Exception) {
            Log.e("WebkioskScraper", "Error extracting links from left frame", e)
        }
        return links
    }

    // Improved session validation that checks for frameset response
    private suspend fun hasValidSession(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Check 1: Session cookie exists
                if (!cookieJar.hasSessionCookie()) {
                    Log.d("WebkioskScraper", "No session cookie found")
                    return@withContext false
                }

                // Check 2: Test with StudentPage.jsp
                val testUrl = "$studentFilesUrl/StudentPage.jsp"
                val request = Request.Builder()
                    .url(testUrl)
                    .get()
                    .header("Cache-Control", "no-cache")
                    .header("Referer", loginUrl)
                    .build()

                val response = client.newCall(request).execute()
                val html = response.body?.string() ?: ""

                Log.d("WebkioskScraper", "Session validation response: ${response.code}")
                Log.d("WebkioskScraper", "Response length: ${html.length}")

                // Check for session timeout indicators
                val sessionTimeoutIndicators = listOf(
                    "session timeout",
                    "session expired",
                    "please login",
                    "login again",
                    "invalid session"
                )

                val hasTimeoutIndicator = sessionTimeoutIndicators.any {
                    html.contains(it, ignoreCase = true)
                }

                // Check if redirected to login page
                val redirectedToLogin = response.request.url.toString().contains("index.jsp")

                // Check for successful frameset response
                val hasValidFrameset = html.contains("<frameset", ignoreCase = true) ||
                        html.contains("FrameLeftStudent.jsp", ignoreCase = true) ||
                        html.contains("JUET", ignoreCase = true)

                val isValid = response.code == 200 &&
                        !hasTimeoutIndicator &&
                        !redirectedToLogin &&
                        hasValidFrameset

                Log.d("WebkioskScraper", "Session validation result: $isValid")
                Log.d("WebkioskScraper", "- Status OK: ${response.code == 200}")
                Log.d("WebkioskScraper", "- No timeout indicators: ${!hasTimeoutIndicator}")
                Log.d("WebkioskScraper", "- Not redirected to login: ${!redirectedToLogin}")
                Log.d("WebkioskScraper", "- Has valid frameset: $hasValidFrameset")

                return@withContext isValid

            } catch (e: Exception) {
                Log.e("WebkioskScraper", "Error validating session", e)
                return@withContext false
            }
        }
    }

    // Updated attendance fetching method that handles frameset structure
    suspend fun getAttendanceFromFrameset(credentials: UserCredentials): Result<List<AttendanceRecord>> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d("WebkioskScraper", "=== Getting Attendance from Frameset ===")

                // Step 1: Ensure we have a valid session
                var hasValidSession = hasValidSession()
                Log.d("WebkioskScraper", "Initial session check: $hasValidSession")

                if (!hasValidSession) {
                    Log.d("WebkioskScraper", "No valid session, attempting login")
                    val loginResult = login(credentials)

                    if (loginResult.isFailure || loginResult.getOrNull() != true) {
                        return@withContext Result.failure(Exception("Could not establish valid session"))
                    }

                    delay(2000) // Wait after login
                    hasValidSession = hasValidSession()
                    Log.d("WebkioskScraper", "Post-login session check: $hasValidSession")

                    if (!hasValidSession) {
                        return@withContext Result.failure(Exception("Session validation failed after login"))
                    }
                }

                // Step 2: Try the direct attendance URL
                val attendanceUrl = "$studentFilesUrl/Academic/StudentAttendanceList.jsp"
                Log.d("WebkioskScraper", "Trying attendance URL: $attendanceUrl")

                val request = Request.Builder()
                    .url(attendanceUrl)
                    .get()
                    .header("Referer", "$studentFilesUrl/StudentPage.jsp")
                    .header("Cache-Control", "no-cache")
                    .build()

                val response = client.newCall(request).execute()
                val html = response.body?.string() ?: ""

                Log.d("WebkioskScraper", "Response code: ${response.code}")
                Log.d("WebkioskScraper", "Response length: ${html.length}")
                Log.d("WebkioskScraper", "Final URL: ${response.request.url}")

                // Check for session timeout
                if (html.contains("session timeout", ignoreCase = true) ||
                    html.contains("please login", ignoreCase = true) ||
                    response.request.url.toString().contains("index.jsp")) {
                    Log.e("WebkioskScraper", "Session timeout detected")
                    return@withContext Result.failure(Exception("Session timeout"))
                }

                if (response.code == 200 && html.length > 100) {
                    // Try to parse attendance data
                    val records = parseAttendanceData(html)
                    if (records.isNotEmpty()) {
                        Log.d("WebkioskScraper", "Successfully found ${records.size} attendance records")
                        return@withContext Result.success(records)
                    } else {
                        Log.d("WebkioskScraper", "No attendance records found in response")
                        // Log first 1000 characters for debugging
                        Log.d("WebkioskScraper", "Response preview: ${html.take(1000)}")
                    }
                }

                return@withContext Result.failure(Exception("Could not find attendance data"))

            } catch (e: Exception) {
                Log.e("WebkioskScraper", "Failed to load attendance", e)
                return@withContext Result.failure(e)
            }
        }
    }

    // Fixed parsing function based on actual HTML structure
    private fun parseAttendanceData(html: String): List<AttendanceRecord> {
        val records = mutableListOf<AttendanceRecord>()

        try {
            val doc = Jsoup.parse(html)

            // Look for the specific table with attendance data
            val table = doc.select("table.sort-table, table[id=table-1]").firstOrNull()
                ?: doc.select("table").find { it.select("thead tr td").any { td ->
                    td.text().contains("Subject", ignoreCase = true)
                }}

            if (table == null) {
                Log.w("WebkioskScraper", "Could not find attendance table")
                return records
            }

            val tbody = table.select("tbody").firstOrNull()
            val rows = tbody?.select("tr") ?: table.select("tr")

            Log.d("WebkioskScraper", "Found ${rows.size} rows in attendance table")

            for (row in rows) {
                val columns = row.select("td")

                // Skip header rows and rows with insufficient columns
                if (columns.size < 6) continue

                // Check if this is a data row (should have a number in first column)
                val snoText = columns[0].text().trim()
                if (!snoText.matches(Regex("\\d+"))) continue

                try {
                    val subject = columns[1].text().trim()

                    // Skip empty subjects
                    if (subject.isBlank()) continue

                    // Parse attendance percentages based on actual HTML structure
                    // Column 2: Lecture+Tutorial(%)
                    // Column 3: Lecture(%)
                    // Column 4: Tutorial(%)
                    // Column 5: Practical(%)

                    val lectureTutorialText = columns[2].text().trim()
                    val lectureText = columns[3].text().trim()
                    val tutorialText = columns[4].text().trim()
                    val practicalText = columns[5].text().trim()

                    val lectureTutorialPercent = parsePercentageFromLink(lectureTutorialText)
                    val lecturePercent = parsePercentageFromLink(lectureText)
                    val tutorialPercent = parsePercentageFromLink(tutorialText)
                    val practicalPercent = parsePercentageFromLink(practicalText)

                    // Calculate overall percentage
                    // If there's a combined Lecture+Tutorial percentage, use that as primary
                    // Otherwise, average the individual components that exist
                    val overallPercent = when {
                        lectureTutorialPercent != null -> lectureTutorialPercent
                        lecturePercent != null && tutorialPercent != null -> (lecturePercent + tutorialPercent) / 2
                        lecturePercent != null -> lecturePercent
                        practicalPercent != null -> practicalPercent
                        else -> 0f
                    }

                    val record = AttendanceRecord(
                        subject = subject,
                        percentage = overallPercent,
                        lecturePercent = lecturePercent,
                        tutorialPercent = tutorialPercent,
                        practicalPercent = practicalPercent,
                        lectureTutorialPercent = lectureTutorialPercent
                    )

                    records.add(record)
                    Log.d("WebkioskScraper", "Parsed record: $subject - Overall: $overallPercent%")

                } catch (e: Exception) {
                    Log.w("WebkioskScraper", "Error parsing attendance row: ${e.message}")
                }
            }

        } catch (e: Exception) {
            Log.e("WebkioskScraper", "Error parsing attendance data", e)
        }

        Log.d("WebkioskScraper", "Total records parsed: ${records.size}")
        return records
    }

    // Enhanced percentage parsing that handles links and various formats
    private fun parsePercentageFromLink(text: String): Float? {
        return try {
            if (text.isBlank() || text.equals("&nbsp;", ignoreCase = true) ||
                text.equals("NA", ignoreCase = true) || text.equals("N/A", ignoreCase = true)) {
                return null
            }

            // Extract numeric value from text (handles links and plain text)
            val numericText = text.replace(Regex("[^0-9.]"), "")

            if (numericText.isBlank()) {
                return null
            }

            numericText.toFloatOrNull()
        } catch (e: Exception) {
            Log.w("WebkioskScraper", "Error parsing percentage from: '$text'", e)
            null
        }
    }

    // Helper method to parse percentage values (keeping original for compatibility)
    private fun parsePercentage(text: String): Float? {
        return try {
            if (text.isBlank() || text.equals("NA", ignoreCase = true) || text.equals("N/A", ignoreCase = true)) {
                null
            } else {
                // Remove % symbol if present and parse
                val cleanText = text.replace("%", "").trim()
                cleanText.toFloatOrNull()
            }
        } catch (e: Exception) {
            null
        }
    }
}