package com.kabirbhasin.docscanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import com.kabirbhasin.docscanner.ui.adaptive.rememberDevicePosture
import com.kabirbhasin.docscanner.ui.home.HomeScreen
import com.kabirbhasin.docscanner.ui.theme.DocumentScannerTheme

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DocumentScannerTheme {
                val windowSizeClass = calculateWindowSizeClass(this)
                val posture by rememberDevicePosture()
                HomeScreen(
                    windowSizeClass = windowSizeClass,
                    devicePosture = posture,
                )
            }
        }
    }
}
