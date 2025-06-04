package com.example.juetapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.juetapp.navigation.AppNavigation
import com.example.juetapp.ui.theme.JUETAPPTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JUETAPPTheme {
                AppNavigation()
            }
        }
    }
}
