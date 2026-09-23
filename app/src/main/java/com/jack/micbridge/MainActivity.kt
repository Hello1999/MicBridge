package com.jack.micbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.jack.micbridge.service.BridgeForegroundService
import com.jack.micbridge.service.ServiceRuntime
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
        // Returning from a recording app or unlocking must not restart the control flow:
        // ACTION_START deliberately blocks, so it would interrupt both normal use and
        // the ongoing microphone verification. The service monitors permission/network
        // boundaries itself; only a stopped service needs a new startup here.
        if (!ServiceRuntime.snapshot.value.serviceRunning) {
            BridgeForegroundService.start(this)
        } else {
            BridgeForegroundService.start(this, BridgeForegroundService.ACTION_REFRESH_STATE)
        }
    }
}
