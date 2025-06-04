package com.example.juetapp.navigation

import WebkioskScraper
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.juetapp.repository.WebkioskRepository
import com.example.juetapp.ui.AttendanceScreen
import com.example.juetapp.ui.LoginScreen
import com.example.juetapp.viewmodel.AttendanceViewModel
import com.example.juetapp.viewmodel.AttendanceViewModelFactory
import com.example.juetapp.viewmodel.LoginViewModel
import com.example.juetapp.viewmodel.LoginViewModelFactory

private val Context.dataStore by preferencesDataStore(name = "webkiosk_settings")

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val context = LocalContext.current

    val scraper = remember { WebkioskScraper() }
    val webkioskRepository = remember {
        WebkioskRepository(scraper, context.dataStore)
    }

    val loginViewModelFactory = remember { LoginViewModelFactory(webkioskRepository) }
    val loginViewModel: LoginViewModel = viewModel(factory = loginViewModelFactory)

    NavHost(navController = navController, startDestination = "login") {
        composable("login") {
            LoginScreen(
                viewModel = loginViewModel,
                // Navigate directly to attendance screen after login
                onLoginSuccess = { navController.navigate("attendance") {
                    // Clear the back stack so user can't go back to login
                    popUpTo("login") { inclusive = true }
                }}
            )
        }
        composable("home") {
            // HomeScreen implementation will be added later
        }
        composable("attendance") {
            val attendanceViewModelFactory = remember { AttendanceViewModelFactory(webkioskRepository) }
            val attendanceViewModel: AttendanceViewModel = viewModel(factory = attendanceViewModelFactory)

            AttendanceScreen(
                viewModel = attendanceViewModel,
                onBackClick = { navController.navigateUp() }
            )
        }
        composable("marks") {
            // MarksScreen implementation will be added later
        }
    }
}