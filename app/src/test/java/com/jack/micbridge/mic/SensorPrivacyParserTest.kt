package com.jack.micbridge.mic

import com.jack.micbridge.data.MicAccessState
import org.junit.Assert.assertEquals
import org.junit.Test

class SensorPrivacyParserTest {
    @Test
    fun `parses only explicit microphone state`() {
        assertEquals(
            MicAccessState.BLOCKED,
            SensorPrivacyRootController.parseMicrophoneState("sensor=1 microphone enabled=true"),
        )
        assertEquals(
            MicAccessState.OPEN,
            SensorPrivacyRootController.parseMicrophoneState("microphone state=false"),
        )
        assertEquals(
            MicAccessState.UNKNOWN,
            SensorPrivacyRootController.parseMicrophoneState("toggle support exists"),
        )
    }

    @Test
    fun `legacy parser rejects substring and mixed-field ambiguity`() {
        assertEquals(
            MicAccessState.UNKNOWN,
            SensorPrivacyRootController.parseMicrophoneState("sensor=10 enabled=false"),
        )
        assertEquals(
            MicAccessState.UNKNOWN,
            SensorPrivacyRootController.parseMicrophoneState(
                "microphone supported=true; camera enabled=false",
            ),
        )
        assertEquals(
            MicAccessState.UNKNOWN,
            SensorPrivacyRootController.parseMicrophoneState(
                "microphone state=false camera enabled=true",
            ),
        )
    }

    @Test
    fun `user scoped read rejects legacy output without user attribution`() {
        assertEquals(
            MicAccessState.UNKNOWN,
            SensorPrivacyRootController.parseMicrophoneState(
                "sensor=1 microphone enabled=true",
                userId = 0,
            ),
        )
        assertEquals(
            MicAccessState.UNKNOWN,
            SensorPrivacyRootController.parseMicrophoneState(
                "microphone state=false",
                userId = 10,
            ),
        )
    }

    @Test
    fun `parses AOSP nested state for only the selected user`() {
        val output = """
            users={
              user_id=0
              sensors={
                sensor=1
                toggles={
                  toggle_type=1
                  state_type=2
                }
              }
              user_id=10
              sensors={
                sensor=1
                toggles={
                  toggle_type=1
                  state_type=1
                }
              }
            }
        """.trimIndent()

        assertEquals(
            MicAccessState.OPEN,
            SensorPrivacyRootController.parseMicrophoneState(output, 0),
        )
        assertEquals(
            MicAccessState.BLOCKED,
            SensorPrivacyRootController.parseMicrophoneState(output, 10),
        )
        assertEquals(
            MicAccessState.UNKNOWN,
            SensorPrivacyRootController.parseMicrophoneState(output, 11),
        )
    }

    @Test
    fun `parses Android 12 AOSP nested is enabled state for exact user and sensor`() {
        val output = """
            individual_enabled_sensor {
              user_id=0
              sensor=1
              is_enabled=false
            }
            individual_enabled_sensor {
              user_id=10
              sensor=1
              is_enabled=true
            }
            individual_enabled_sensor {
              user_id=10
              sensor=2
              is_enabled=false
            }
        """.trimIndent()

        assertEquals(
            MicAccessState.OPEN,
            SensorPrivacyRootController.parseMicrophoneState(output, 0),
        )
        assertEquals(
            MicAccessState.BLOCKED,
            SensorPrivacyRootController.parseMicrophoneState(output, 10),
        )
        assertEquals(
            MicAccessState.UNKNOWN,
            SensorPrivacyRootController.parseMicrophoneState(output, 11),
        )
    }

    @Test
    fun `global all-sensor privacy vetoes a disabled per-user microphone toggle`() {
        val output = """
            {
              is_enabled=true
              users={
                user_id=0
                sensors={
                  sensor=1
                  toggles={
                    state_type=2
                  }
                }
              }
            }
        """.trimIndent()

        assertEquals(
            MicAccessState.BLOCKED,
            SensorPrivacyRootController.parseMicrophoneState(output, 0),
        )
    }

    @Test
    fun `unknown microphone state can never be collapsed into open`() {
        val output = """
            {
              users={
                user_id=0
                sensors={
                  sensor=1
                  toggles={
                    state_type=3
                  }
                  toggles={
                    state_type=2
                  }
                }
              }
            }
        """.trimIndent()

        assertEquals(
            MicAccessState.UNKNOWN,
            SensorPrivacyRootController.parseMicrophoneState(output, 0),
        )
    }
}
