package com.jack.micbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.jack.micbridge.service.BridgeForegroundService
import com.jack.micbridge.ui.MicBridgeApp
import com.jack.micbridge.ui.MicBridgeTheme

class MainActivity : ComponentActivity() {
    private var resumeGeneration by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MicBridgeTheme { MicBridgeApp(resumeGeneration) } }
    }

    override fun onResume() {
        super.onResume()
        resumeGeneration++
        // Returning from system settings remains a trust boundary: start first blocks,
        // verifies the controller and only then restores a fresh listener generation.
        BridgeForegroundService.start(this)
    }
}
