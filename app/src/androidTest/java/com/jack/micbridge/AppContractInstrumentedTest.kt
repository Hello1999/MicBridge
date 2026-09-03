package com.jack.micbridge

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jack.micbridge.data.DirectBootSettings
import com.jack.micbridge.data.BeginRequestResult
import com.jack.micbridge.data.MicAccessState
import com.jack.micbridge.data.RequestLedger
import com.jack.micbridge.data.SettingsRepository
import com.jack.micbridge.data.SafetyTarget
import com.jack.micbridge.receiver.BootReceiver
import com.jack.micbridge.receiver.EmergencyBlockReceiver
import com.jack.micbridge.service.BridgeForegroundService
import com.jack.micbridge.service.ServiceRuntime
import com.jack.micbridge.safety.ActiveSafetyLease
import com.jack.micbridge.safety.BootIncidentStore
import com.jack.micbridge.safety.SafetyLeaseStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class AppContractInstrumentedTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun manifestNeverRequestsAudioCapturePermission() {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
        )

        assertFalse(info.requestedPermissions.orEmpty().contains(Manifest.permission.RECORD_AUDIO))
    }

    @Test
    fun visibleActivityLaunchAutomaticallyStartsFailClosedForegroundService() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            val deadline = SystemClock.elapsedRealtime() + 5_000L
            while (!ServiceRuntime.snapshot.value.serviceRunning &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                SystemClock.sleep(25L)
            }

            assertTrue(ServiceRuntime.snapshot.value.serviceRunning)
            assertFalse(ServiceRuntime.snapshot.value.micAccess == MicAccessState.OPEN)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun credentialsLedgersAndDeviceProtectedStateAreExcludedFromEveryTransferMode() {
        val app = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.ApplicationInfoFlags.of(0),
        )
        assertEquals(0, app.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)

        val expectedDomains = setOf(
            "root",
            "file",
            "database",
            "sharedpref",
            "external",
            "device_root",
            "device_file",
            "device_database",
            "device_sharedpref",
        )
        val exclusions = mutableMapOf<String, MutableSet<String>>()
        val parser = context.resources.getXml(R.xml.data_extraction_rules)
        var section: String? = null
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "cloud-backup", "device-transfer" -> {
                        section = parser.name
                        exclusions.getOrPut(parser.name) { linkedSetOf() }
                    }
                    "exclude" -> if (parser.getAttributeValue(null, "path") == ".") {
                        section?.let { exclusions.getValue(it) += parser.getAttributeValue(null, "domain") }
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == section) section = null
            }
            parser.next()
        }

        assertEquals(expectedDomains, exclusions["cloud-backup"])
        assertEquals(expectedDomains, exclusions["device-transfer"])
    }

    @Test
    fun controlComponentsHaveFailClosedExposure() {
        val manager = context.packageManager
        val service = manager.getServiceInfo(
            ComponentName(context, BridgeForegroundService::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )
        val emergency = manager.getReceiverInfo(
            ComponentName(context, EmergencyBlockReceiver::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )
        val boot = manager.getReceiverInfo(
            ComponentName(context, BootReceiver::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )

        assertFalse(service.exported)
        assertTrue(
            service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE != 0,
        )
        assertFalse(emergency.exported)
        assertTrue(boot.exported)
        assertTrue(boot.directBootAware)
    }

    @Test
    fun directBootStoreContainsOnlyMinimalBlockingTarget() {
        val store = DirectBootSettings(context)
        val originalController = store.controllerId
        val originalPackage = store.targetPackage
        val originalUser = store.userId
        try {
            // The device-protected format remains able to read an old development install so
            // its first post-update blocker can retire that target without opening it.
            assertTrue(
                store.mirror(
                    SettingsRepository.CONTROLLER_APP_OPS,
                    SettingsRepository.DEFAULT_CHATGPT_PACKAGE,
                ),
            )

            assertEquals(SettingsRepository.CONTROLLER_APP_OPS, store.controllerId)
            assertEquals(SettingsRepository.DEFAULT_CHATGPT_PACKAGE, store.targetPackage)
            assertTrue(store.userId >= 0)
        } finally {
            store.mirror(originalController, originalPackage, originalUser)
        }
    }

    @Test
    fun activeSafetyLeaseRoundTripsExactTargetInDeviceProtectedStorage() {
        val store = SafetyLeaseStore(context)
        val expected = ActiveSafetyLease(
            requestId = "instrumented-lease-0001",
            target = SafetyTarget(
                SettingsRepository.CONTROLLER_SENSOR_PRIVACY,
                "android-global-microphone",
                10,
            ),
            deadlineEpochMs = 123_456L,
            deadlineElapsedRealtimeMs = 45_678L,
            exactAlarmArmed = true,
            rootWatchdogArmed = false,
        )
        try {
            assertTrue(store.save(expected))
            assertEquals(expected, store.load())
        } finally {
            store.clear()
        }
    }

    @Test
    fun bootFailureEvidencePersistsInDeviceProtectedStorageUntilExplicitlyCleared() {
        val store = BootIncidentStore(context)
        try {
            assertTrue(store.record("instrumented boot failure"))
            assertEquals("instrumented boot failure", store.message())
            assertTrue(store.clear())
            assertEquals(null, store.message())
        } finally {
            store.clear()
        }
    }

    @Test
    fun releaseSettingsRejectMutableAppOpsControllerAndMirrorOnlyReleaseSelection() {
        val settings = SettingsRepository(context)
        val directBoot = DirectBootSettings(context)
        val originalController = settings.controllerId
        try {
            settings.originalAppOpsMode = null
            settings.targetPackage = SettingsRepository.DEFAULT_CHATGPT_PACKAGE
            settings.controllerId = SettingsRepository.CONTROLLER_SENSOR_PRIVACY

            assertTrue(
                runCatching {
                    settings.controllerId = SettingsRepository.CONTROLLER_APP_OPS
                }.isFailure,
            )
            assertEquals(SettingsRepository.CONTROLLER_SENSOR_PRIVACY, settings.controllerId)
            assertEquals(SettingsRepository.CONTROLLER_SENSOR_PRIVACY, directBoot.controllerId)
        } finally {
            settings.originalAppOpsMode = null
            settings.controllerId = originalController
        }
    }

    @Test
    fun pendingAppOpsStateLocksControllerAndTargetUntilSafeCleanup() {
        val settings = SettingsRepository(context)
        val originalController = settings.controllerId
        val originalPackage = settings.targetPackage
        try {
            settings.originalAppOpsMode = null
            settings.targetPackage = SettingsRepository.DEFAULT_CHATGPT_PACKAGE
            assertTrue(
                settings.saveOriginalAppOps(
                    "default",
                    SettingsRepository.DEFAULT_CHATGPT_PACKAGE,
                    android.os.Process.myUid() / 100_000,
                ),
            )

            assertTrue(settings.hasPendingAppOpsState)
            assertTrue(
                runCatching {
                    settings.controllerId = if (
                        originalController == SettingsRepository.CONTROLLER_AUDIO_MANAGER
                    ) SettingsRepository.CONTROLLER_SENSOR_PRIVACY
                    else SettingsRepository.CONTROLLER_AUDIO_MANAGER
                }.isFailure,
            )
            assertTrue(
                runCatching { settings.targetPackage = "com.example.other" }.isFailure,
            )
            assertEquals(originalController, settings.controllerId)
            assertEquals(SettingsRepository.DEFAULT_CHATGPT_PACKAGE, settings.targetPackage)
        } finally {
            settings.originalAppOpsMode = null
            settings.controllerId = originalController
            settings.targetPackage = originalPackage
        }
    }

    @Test
    fun appOpsIgnoreOwnershipIsBoundToCapturedModePackageAndUser() {
        val settings = SettingsRepository(context)
        val packageName = SettingsRepository.DEFAULT_CHATGPT_PACKAGE
        try {
            settings.originalAppOpsMode = null
            assertFalse(settings.markAppOpsIgnoreOwned(packageName, 10))

            assertTrue(settings.saveOriginalAppOps("allow", packageName, 10))
            assertTrue(settings.markAppOpsIgnoreOwned(packageName, 10))
            assertTrue(settings.ownsAppOpsIgnore(packageName, 10))
            assertFalse(settings.ownsAppOpsIgnore(packageName, 11))
            assertFalse(settings.ownsAppOpsIgnore("com.example.other", 10))

            // Capturing a new baseline invalidates the previous ownership proof atomically.
            assertTrue(settings.saveOriginalAppOps("default", packageName, 10))
            assertFalse(settings.ownsAppOpsIgnore(packageName, 10))
            assertTrue(settings.markAppOpsIgnoreOwned(packageName, 10))
            assertTrue(settings.clearAppOpsIgnoreOwnership())
            assertFalse(settings.ownsAppOpsIgnore(packageName, 10))
        } finally {
            settings.originalAppOpsMode = null
        }
    }

    @Test
    fun rotatingTokenImmediatelyInvalidatesOldTokenAndAdvancesGeneration() {
        val settings = SettingsRepository(context)
        val oldToken = settings.token
        val oldGeneration = settings.tokenGeneration

        val newToken = settings.rotateToken()

        assertFalse(oldToken == newToken)
        assertFalse(settings.tokenMatches(oldToken))
        assertTrue(settings.tokenMatches(newToken))
        assertEquals(oldGeneration + 1, settings.tokenGeneration)
    }

    @Test
    fun requestIdsAreNeverEvictedOrReexecutedAcrossAgeVolumeAndTokenRotation() {
        val databaseName = "request-ledger-retention-${System.nanoTime()}.db"
        var ledger = RequestLedger(context, databaseName)
        val oldTimestamp = 1_000L
        val originalRequestId = "retained-request-0000"
        try {
            repeat(300) { index ->
                val requestId = "retained-request-${index.toString().padStart(4, '0')}"
                assertEquals(
                    BeginRequestResult.New,
                    ledger.begin(
                        requestId = requestId,
                        endpoint = "/v1/mic/toggle",
                        tokenGeneration = 7,
                        nowEpochMs = oldTimestamp + index,
                    ),
                )
                ledger.complete(
                    requestId = requestId,
                    outcome = MicAccessState.BLOCKED,
                    ok = true,
                    errorCode = null,
                    errorMessage = null,
                    nowEpochMs = oldTimestamp + index,
                )
            }

            // This later insert would previously prune both by age (24 h) and row count (256).
            assertEquals(
                BeginRequestResult.New,
                ledger.begin(
                    requestId = "later-request-0300",
                    endpoint = "/v1/mic/toggle",
                    tokenGeneration = 7,
                    nowEpochMs = oldTimestamp + 2L * 24L * 60L * 60L * 1_000L,
                ),
            )
            assertEquals(
                BeginRequestResult.New,
                ledger.begin(
                    requestId = "persisted-in-progress-0001",
                    endpoint = "/v1/mic/open",
                    tokenGeneration = 7,
                    nowEpochMs = oldTimestamp + 301L,
                ),
            )

            // Prove the invariant is durable, not merely an in-memory property of one helper.
            ledger.close()
            ledger = RequestLedger(context, databaseName)

            val sameGeneration = ledger.begin(
                requestId = originalRequestId,
                endpoint = "/v1/mic/toggle",
                tokenGeneration = 7,
                nowEpochMs = oldTimestamp + 3L * 24L * 60L * 60L * 1_000L,
            )
            assertTrue(sameGeneration is BeginRequestResult.Existing)

            val rotatedGeneration = ledger.begin(
                requestId = originalRequestId,
                endpoint = "/v1/mic/toggle",
                tokenGeneration = 8,
                nowEpochMs = oldTimestamp + 4L * 24L * 60L * 60L * 1_000L,
            )
            assertTrue(rotatedGeneration is BeginRequestResult.Conflict)

            val endpointConflict = ledger.begin(
                requestId = originalRequestId,
                endpoint = "/v1/mic/block",
                tokenGeneration = 7,
                nowEpochMs = oldTimestamp + 5L * 24L * 60L * 60L * 1_000L,
            )
            assertTrue(endpointConflict is BeginRequestResult.Conflict)

            val inProgressReplay = ledger.begin(
                requestId = "persisted-in-progress-0001",
                endpoint = "/v1/mic/open",
                tokenGeneration = 7,
                nowEpochMs = oldTimestamp + 6L * 24L * 60L * 60L * 1_000L,
            )
            assertTrue(inProgressReplay is BeginRequestResult.Existing)
            assertEquals(
                RequestLedger.STATUS_IN_PROGRESS,
                (inProgressReplay as BeginRequestResult.Existing).entry.status,
            )
        } finally {
            ledger.close()
            context.deleteDatabase(databaseName)
        }
    }
}
