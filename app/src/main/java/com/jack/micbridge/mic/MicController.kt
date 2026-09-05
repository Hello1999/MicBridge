package com.jack.micbridge.mic

import com.jack.micbridge.data.ControlResult
import com.jack.micbridge.data.MicAccessState
import com.jack.micbridge.data.ProbeResult
import com.jack.micbridge.data.SafetyTarget

data class OpenAuthorization(
    val requestId: String,
    val validUntilElapsedRealtimeMs: Long,
    val persistent: Boolean = false,
)

interface MicController {
    val id: String
    val displayName: String

    suspend fun probe(): ProbeResult
    suspend fun captureSafetyTarget(): SafetyTarget?
    suspend fun block(target: SafetyTarget? = null): ControlResult
    suspend fun open(target: SafetyTarget, authorization: OpenAuthorization): ControlResult
    suspend fun readState(target: SafetyTarget? = null): MicAccessState
}
