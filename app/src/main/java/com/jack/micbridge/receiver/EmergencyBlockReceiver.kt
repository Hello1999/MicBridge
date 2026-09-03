package com.jack.micbridge.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jack.micbridge.data.SafetyTarget
import com.jack.micbridge.safety.FailsafeBlocker
import com.jack.micbridge.safety.AutoBlockSafety
import com.jack.micbridge.safety.LeaseExpiryResult
import com.jack.micbridge.safety.SafetyLeaseStore
import com.jack.micbridge.safety.BootIncidentStore
import com.jack.micbridge.safety.handleCurrentLeaseExpiry
import com.jack.micbridge.service.BridgeForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class EmergencyBlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != AutoBlockSafety.ACTION_AUTO_BLOCK) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val applicationContext = context.applicationContext
            val incidentStore = BootIncidentStore(applicationContext)
            try {
                val controllerId = intent.getStringExtra(AutoBlockSafety.EXTRA_CONTROLLER_ID)
                val targetPackage = intent.getStringExtra(AutoBlockSafety.EXTRA_TARGET_PACKAGE)
                val userId = intent.getIntExtra(AutoBlockSafety.EXTRA_USER_ID, -1)
                val requestId = intent.getStringExtra(AutoBlockSafety.EXTRA_REQUEST_ID)
                if (
                    controllerId != null && targetPackage != null && requestId != null && userId >= 0
                ) {
                    val expectedTarget = SafetyTarget(controllerId, targetPackage, userId)
                    val result = withTimeoutOrNull(RECEIVER_WORK_TIMEOUT_MS) {
                        handleCurrentLeaseExpiry(
                            expectedRequestId = requestId,
                            expectedTarget = expectedTarget,
                            loadLease = { SafetyLeaseStore(applicationContext).load() },
                            block = { target ->
                                FailsafeBlocker.block(
                                    applicationContext,
                                    target.controllerId,
                                    target.targetPackage,
                                    target.userId,
                                )
                            },
                        )
                    }
                    when (result) {
                        null -> incidentStore.alert("安全闹钟屏蔽超时；麦克风状态未知")
                        LeaseExpiryResult.BLOCK_FAILED ->
                            incidentStore.alert("安全闹钟到期 BLOCK 读回失败；麦克风状态未知")
                        LeaseExpiryResult.BLOCKED -> Unit
                        LeaseExpiryResult.STALE -> return@launch
                    }
                    if (result != LeaseExpiryResult.STALE) {
                        // Reconcile a live service notification, or restart a killed service
                        // into its startup BLOCK path. Stale alarm generations do nothing.
                        val serviceStarted = runCatching {
                            BridgeForegroundService.start(
                                applicationContext,
                                BridgeForegroundService.ACTION_BLOCK,
                            )
                        }.isSuccess
                        if (!serviceStarted) {
                            incidentStore.alert("安全闹钟处理后无法启动前台服务复核；请确认系统麦克风隐私开关")
                        }
                    }
                } else {
                    incidentStore.alert("安全闹钟参数无效；麦克风状态未知")
                }
            } catch (_: Throwable) {
                incidentStore.alert("安全闹钟屏蔽流程异常；麦克风状态未知")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val RECEIVER_WORK_TIMEOUT_MS = 8_000L
    }

    private fun BootIncidentStore.alert(message: String) {
        record(message)
        postFailureNotification()
    }
}
