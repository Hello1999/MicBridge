package com.jack.micbridge.service

/** Permissions for local system control are independent from permission to expose HTTP. */
internal enum class PermissionSafetyAction { NONE, REVOKE_AND_BLOCK, REFRESH_REMOTE }

internal fun permissionSafetyAction(
    corePermissionsUsable: Boolean,
    localNetworkPermissionGranted: Boolean,
    previousRemotePermissionsUsable: Boolean,
    hasRemoteAuthority: Boolean,
    hasActiveAuthorityOrLease: Boolean,
): PermissionSafetyAction = when {
    !corePermissionsUsable && hasActiveAuthorityOrLease -> PermissionSafetyAction.REVOKE_AND_BLOCK
    !localNetworkPermissionGranted && (hasRemoteAuthority || previousRemotePermissionsUsable) ->
        PermissionSafetyAction.REVOKE_AND_BLOCK
    corePermissionsUsable && localNetworkPermissionGranted && !previousRemotePermissionsUsable ->
        PermissionSafetyAction.REFRESH_REMOTE
    else -> PermissionSafetyAction.NONE
}
