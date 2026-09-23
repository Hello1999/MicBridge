package com.jack.micbridge.service

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionSafetyPolicyTest {
    @Test
    fun `local open without LAN permission retains its independently guarded lease`() {
        assertEquals(
            PermissionSafetyAction.NONE,
            action(lan = false, previouslyRemoteUsable = false, remote = false, active = true),
        )
    }

    @Test
    fun `foreground notification or battery protection loss blocks local open without LAN`() {
        assertEquals(
            PermissionSafetyAction.REVOKE_AND_BLOCK,
            action(core = false, lan = false, previouslyRemoteUsable = false, remote = false, active = true),
        )
    }

    @Test
    fun `LAN permission loss revokes a remote listener even when prior check was unavailable`() {
        assertEquals(
            PermissionSafetyAction.REVOKE_AND_BLOCK,
            action(lan = false, previouslyRemoteUsable = false, remote = true, active = true),
        )
    }

    @Test
    fun `LAN revocation remains fail closed when listener already lost its address`() {
        assertEquals(
            PermissionSafetyAction.REVOKE_AND_BLOCK,
            action(lan = false, previouslyRemoteUsable = true, remote = false, active = true),
        )
    }

    @Test
    fun `new remote permission requests a safe listener rebind`() {
        assertEquals(
            PermissionSafetyAction.REFRESH_REMOTE,
            action(lan = true, previouslyRemoteUsable = false, remote = false, active = false),
        )
    }

    @Test
    fun `unchanged permissions do not interrupt a calibration or normal open`() {
        assertEquals(
            PermissionSafetyAction.NONE,
            action(lan = true, previouslyRemoteUsable = true, remote = false, active = true),
        )
    }

    @Test
    fun `idle missing permissions do not claim authority or repeatedly rebind`() {
        assertEquals(
            PermissionSafetyAction.NONE,
            action(core = false, lan = false, previouslyRemoteUsable = false, remote = false, active = false),
        )
    }

    private fun action(
        core: Boolean = true,
        lan: Boolean,
        previouslyRemoteUsable: Boolean,
        remote: Boolean,
        active: Boolean,
    ) = permissionSafetyAction(core, lan, previouslyRemoteUsable, remote, active)
}
