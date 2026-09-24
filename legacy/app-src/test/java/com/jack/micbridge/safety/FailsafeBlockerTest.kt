package com.jack.micbridge.safety

import com.jack.micbridge.data.SettingsRepository
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailsafeBlockerTest {
    @Test
    fun `immutable alarm blocker preserves audio composite fallback order`() {
        assertEquals(
            listOf(
                SettingsRepository.CONTROLLER_AUDIO_MANAGER,
                SettingsRepository.CONTROLLER_SENSOR_PRIVACY,
            ),
            immutableFailsafeBlockPlan(SettingsRepository.CONTROLLER_AUDIO_MANAGER),
        )
    }

    @Test
    fun `immutable alarm blocker preserves sensor composite fallback order`() {
        assertEquals(
            listOf(
                SettingsRepository.CONTROLLER_SENSOR_PRIVACY,
                SettingsRepository.CONTROLLER_AUDIO_MANAGER,
            ),
            immutableFailsafeBlockPlan(SettingsRepository.CONTROLLER_SENSOR_PRIVACY),
        )
    }

    @Test
    fun `legacy appops alarm target is retired through global gates without appops mutation`() {
        assertEquals(
            listOf(
                SettingsRepository.CONTROLLER_SENSOR_PRIVACY,
                SettingsRepository.CONTROLLER_AUDIO_MANAGER,
            ),
            immutableFailsafeBlockPlan(SettingsRepository.CONTROLLER_APP_OPS),
        )
    }

    @Test
    fun `unknown immutable controller has no unsafe fallback`() {
        assertEquals(emptyList<String>(), immutableFailsafeBlockPlan("unknown"))
    }

    @Test
    fun `immutable executor attempts every gate and requires every result`() = runTest {
        val attempted = mutableListOf<String>()

        val result = executeImmutableFailsafeBlockPlan(
            SettingsRepository.CONTROLLER_AUDIO_MANAGER,
        ) { gate ->
            attempted += gate
            gate == SettingsRepository.CONTROLLER_AUDIO_MANAGER
        }

        assertFalse(result)
        assertEquals(
            immutableFailsafeBlockPlan(SettingsRepository.CONTROLLER_AUDIO_MANAGER),
            attempted,
        )
    }

    @Test
    fun `immutable executor continues after an ordinary gate exception`() = runTest {
        val attempted = mutableListOf<String>()

        val result = executeImmutableFailsafeBlockPlan(
            SettingsRepository.CONTROLLER_AUDIO_MANAGER,
        ) { gate ->
            attempted += gate
            when (gate) {
                SettingsRepository.CONTROLLER_AUDIO_MANAGER -> throw IllegalStateException("audio failed")
                else -> true
            }
        }

        assertFalse(result)
        assertEquals(
            immutableFailsafeBlockPlan(SettingsRepository.CONTROLLER_AUDIO_MANAGER),
            attempted,
        )
    }

    @Test
    fun `immutable executor returns false when no gate verifies blocked`() = runTest {
        val attempted = mutableListOf<String>()

        val result = executeImmutableFailsafeBlockPlan(
            SettingsRepository.CONTROLLER_SENSOR_PRIVACY,
        ) { gate ->
            attempted += gate
            false
        }

        assertFalse(result)
        assertEquals(
            immutableFailsafeBlockPlan(SettingsRepository.CONTROLLER_SENSOR_PRIVACY),
            attempted,
        )
    }

    @Test
    fun `immutable executor never swallows coroutine cancellation`() = runTest {
        var cancelled = false
        try {
            executeImmutableFailsafeBlockPlan(
                SettingsRepository.CONTROLLER_AUDIO_MANAGER,
            ) {
                throw CancellationException("deadline")
            }
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
    }

}
