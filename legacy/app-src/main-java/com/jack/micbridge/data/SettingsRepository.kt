package com.jack.micbridge.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import com.jack.micbridge.BuildConfig
import com.jack.micbridge.server.AuthGuard

class SettingsRepository(context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val directBootSettings = DirectBootSettings(context)

    /**
     * Memoized [currentAcousticCalibrationIdentity]. Both fields are only read and written while
     * holding [CALIBRATION_IDENTITY_LOCK], which also serializes every mutating path here.
     */
    private var calibrationIdentityCache: AcousticCalibrationIdentity? = null
    private var calibrationIdentityCachedAtNanos: Long? = null

    init {
        // root_appops was available only during development. AppOps exposes the resulting
        // value but not its last writer, so an identical external `ignore` written during an
        // OPEN lease cannot be distinguished from MicBridge's old marker. Never make that
        // ownership claim in the release path: migrate the selection to the global Root gate
        // while retaining any legacy metadata for the explicit, non-restoring cleanup flow.
        val storedController = preferences.getString(KEY_CONTROLLER, CONTROLLER_AUDIO_MANAGER)
        if (storedController !in RELEASE_CONTROLLERS) {
            check(
                preferences.edit()
                    .putString(KEY_CONTROLLER, CONTROLLER_SENSOR_PRIVACY)
                    .commit(),
            ) { "不安全的旧控制器迁移失败" }
            invalidateCalibrationIdentityCache()
        }
        mirrorDirectBootController()
    }

    val token: String
        get() = preferences.getString(KEY_TOKEN, null) ?: generateToken().also {
            check(preferences.edit().putString(KEY_TOKEN, it).commit()) {
                "令牌持久化失败"
            }
        }

    val tokenGeneration: Int
        get() = preferences.getInt(KEY_TOKEN_GENERATION, 1)

    var controllerId: String
        get() = preferences.getString(KEY_CONTROLLER, CONTROLLER_AUDIO_MANAGER)
            ?: CONTROLLER_AUDIO_MANAGER
        set(value) {
            synchronized(CALIBRATION_IDENTITY_LOCK) {
                require(value in RELEASE_CONTROLLERS) {
                    "发布版仅支持 AudioManager 与 Root sensor_privacy 控制器"
                }
                if (value != controllerId) {
                    check(!hasPendingAppOpsState) {
                        "存在尚未安全恢复的 AppOps 状态；禁止更换控制器"
                    }
                }
                val changed = value != controllerId
                val editor = preferences.edit().putString(KEY_CONTROLLER, value)
                if (changed) {
                    editor.putLong(
                        KEY_CALIBRATION_SETTINGS_GENERATION,
                        Math.addExact(calibrationSettingsGeneration, 1L),
                    )
                }
                check(editor.commit()) {
                    "控制器设置持久化失败"
                }
                invalidateCalibrationIdentityCache()
                check(mirrorDirectBootController()) { "Direct Boot 控制器镜像失败" }
            }
        }

    /** Legacy experimental AppOps metadata only; never selects the system control target. */
    var targetPackage: String
        get() = preferences.getString(KEY_TARGET_PACKAGE, DEFAULT_CHATGPT_PACKAGE)
            ?: DEFAULT_CHATGPT_PACKAGE
        set(value) {
            check(preferences.edit().putString(KEY_TARGET_PACKAGE, value).commit()) {
                "旧目标包元数据持久化失败"
            }
        }

    var originalAppOpsMode: String?
        get() = preferences.getString(KEY_ORIGINAL_APPOPS_MODE, null)
        set(value) {
            preferences.edit().apply {
                if (value == null) {
                    remove(KEY_ORIGINAL_APPOPS_MODE)
                    remove(KEY_ORIGINAL_APPOPS_PACKAGE)
                    remove(KEY_ORIGINAL_APPOPS_USER)
                    remove(KEY_OWNED_APPOPS_IGNORE_MODE)
                    remove(KEY_OWNED_APPOPS_IGNORE_PACKAGE)
                    remove(KEY_OWNED_APPOPS_IGNORE_USER)
                }
                else putString(KEY_ORIGINAL_APPOPS_MODE, value)
            }.commit().also { check(it) { "AppOps 原态设置持久化失败" } }
            invalidateCalibrationIdentityCache()
            check(mirrorDirectBootController()) { "Direct Boot AppOps 镜像失败" }
        }

    val originalAppOpsPackage: String?
        get() = preferences.getString(KEY_ORIGINAL_APPOPS_PACKAGE, null)

    val originalAppOpsUserId: Int?
        get() = preferences.getInt(KEY_ORIGINAL_APPOPS_USER, -1).takeIf { it >= 0 }

    val hasPendingAppOpsState: Boolean
        get() = originalAppOpsMode != null ||
            preferences.contains(KEY_OWNED_APPOPS_IGNORE_MODE) ||
            preferences.contains(KEY_OWNED_APPOPS_IGNORE_PACKAGE) ||
            preferences.contains(KEY_OWNED_APPOPS_IGNORE_USER)

    fun saveOriginalAppOps(mode: String, packageName: String, userId: Int): Boolean {
        val saved = preferences.edit()
            .putString(KEY_ORIGINAL_APPOPS_MODE, mode)
            .putString(KEY_ORIGINAL_APPOPS_PACKAGE, packageName)
            .putInt(KEY_ORIGINAL_APPOPS_USER, userId)
            // A newly captured baseline has not yet been changed to ignore by MicBridge.
            .remove(KEY_OWNED_APPOPS_IGNORE_MODE)
            .remove(KEY_OWNED_APPOPS_IGNORE_PACKAGE)
            .remove(KEY_OWNED_APPOPS_IGNORE_USER)
            .commit()
        invalidateCalibrationIdentityCache()
        return saved && mirrorDirectBootController()
    }

    /** Legacy experimental metadata. Release code never selects or restores the AppOps gate. */
    fun markAppOpsIgnoreOwned(packageName: String, userId: Int): Boolean {
        val original = originalAppOpsMode ?: return false
        if (
            original !in RESTORABLE_APPOPS_MODES ||
            originalAppOpsPackage != packageName ||
            originalAppOpsUserId != userId
        ) return false
        return preferences.edit()
            .putString(KEY_OWNED_APPOPS_IGNORE_MODE, original)
            .putString(KEY_OWNED_APPOPS_IGNORE_PACKAGE, packageName)
            .putInt(KEY_OWNED_APPOPS_IGNORE_USER, userId)
            .commit()
            .also { invalidateCalibrationIdentityCache() }
    }

    fun ownsAppOpsIgnore(packageName: String, userId: Int): Boolean {
        val original = originalAppOpsMode ?: return false
        return original in RESTORABLE_APPOPS_MODES &&
            originalAppOpsPackage == packageName &&
            originalAppOpsUserId == userId &&
            preferences.getString(KEY_OWNED_APPOPS_IGNORE_MODE, null) == original &&
            preferences.getString(KEY_OWNED_APPOPS_IGNORE_PACKAGE, null) == packageName &&
            preferences.getInt(KEY_OWNED_APPOPS_IGNORE_USER, -1) == userId
    }

    fun clearAppOpsIgnoreOwnership(): Boolean = preferences.edit()
        .remove(KEY_OWNED_APPOPS_IGNORE_MODE)
        .remove(KEY_OWNED_APPOPS_IGNORE_PACKAGE)
        .remove(KEY_OWNED_APPOPS_IGNORE_USER)
        .commit()
        .also { invalidateCalibrationIdentityCache() }

    /** Before unlock, release controllers always address the system microphone. */
    fun mirrorDirectBootController(): Boolean =
        directBootSettings.mirror(controllerId, GLOBAL_MIC_TARGET)

    var port: Int
        get() = preferences.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) {
            require(value in 1024..65535)
            check(preferences.edit().putInt(KEY_PORT, value).commit()) { "端口设置持久化失败" }
        }

    var maxOpenSeconds: Int
        get() = preferences.getInt(KEY_MAX_OPEN_SECONDS, DEFAULT_MAX_OPEN_SECONDS)
            .coerceIn(MIN_OPEN_SECONDS, MAX_OPEN_SECONDS)
        set(value) {
            require(value in MIN_OPEN_SECONDS..MAX_OPEN_SECONDS)
            check(preferences.edit().putInt(KEY_MAX_OPEN_SECONDS, value).commit()) {
                "开放时限持久化失败"
            }
        }

    var autoStart: Boolean
        get() = preferences.getBoolean(KEY_AUTO_START, false)
        set(value) {
            check(preferences.edit().putBoolean(KEY_AUTO_START, value).commit()) {
                "自动启动设置持久化失败"
            }
        }

    var reliableMode: Boolean
        get() = preferences.getBoolean(KEY_RELIABLE_MODE, true)
        set(value) {
            check(preferences.edit().putBoolean(KEY_RELIABLE_MODE, value).commit()) {
                "可靠模式设置持久化失败"
            }
        }

    var rootBootGuardInstalled: Boolean
        get() = preferences.getBoolean(KEY_ROOT_BOOT_GUARD, false)
        set(value) {
            check(preferences.edit().putBoolean(KEY_ROOT_BOOT_GUARD, value).commit()) {
                "Root 开机保护状态持久化失败"
            }
        }

    fun tokenMatches(candidate: String?): Boolean {
        return AuthGuard.matches(token, candidate)
    }

    fun rotateToken(): String {
        val next = generateToken()
        check(preferences.edit()
            .putString(KEY_TOKEN, next)
            .putInt(KEY_TOKEN_GENERATION, tokenGeneration + 1)
            .commit()) { "令牌轮换持久化失败" }
        // The token is not part of the identity; invalidating anyway keeps "every mutating path
        // drops the memo" an invariant that cannot rot as this class grows.
        invalidateCalibrationIdentityCache()
        return next
    }

    /**
     * Persists only the identity frozen at the start of the completed calibration round.
     * A fresh capture must still match, but that fresh capture is never substituted for the
     * tested value. Therefore a concurrent system/configuration change either rejects
     * the commit or immediately makes the saved proof invalid.
     */
    fun markAcousticCalibrationPassed(
        testedIdentity: AcousticCalibrationIdentity,
    ): Boolean = synchronized(CALIBRATION_IDENTITY_LOCK) {
        // Persist only a fresh system identity that matches the completed round.
        invalidateCalibrationIdentityCache()
        if (currentAcousticCalibrationIdentity() != testedIdentity) return@synchronized false
        preferences.edit()
            .putLong(
                KEY_CALIBRATED_SETTINGS_GENERATION,
                testedIdentity.settingsGeneration,
            )
            .putString(KEY_CALIBRATED_FINGERPRINT, testedIdentity.androidFingerprint)
            .putString(KEY_CALIBRATED_ANDROID_BUILD_ID, testedIdentity.androidBuildId)
            .putString(KEY_CALIBRATED_SCOPE, SYSTEM_CALIBRATION_SCOPE)
            .remove(KEY_CALIBRATED_PACKAGE)
            .remove(KEY_CALIBRATED_PACKAGE_VERSION)
            .remove(KEY_CALIBRATED_PACKAGE_UID)
            .remove(KEY_CALIBRATED_PACKAGE_LAST_UPDATE_TIME)
            .remove(KEY_CALIBRATED_PACKAGE_SIGNING_CERT_SHA256)
            .putString(KEY_CALIBRATED_CONTROLLER, testedIdentity.controllerId)
            .putString(KEY_CALIBRATED_APP_BUILD_ID, testedIdentity.micBridgeBuildId)
            .putInt(KEY_CALIBRATED_ANDROID_USER_ID, testedIdentity.androidUserId)
            .putLong(KEY_CALIBRATED_AT, System.currentTimeMillis())
            .commit()
            .also { invalidateCalibrationIdentityCache() }
    }

    fun clearCalibration() = synchronized(CALIBRATION_IDENTITY_LOCK) {
        preferences.edit()
            .remove(KEY_CALIBRATED_SCOPE)
            .remove(KEY_CALIBRATED_SETTINGS_GENERATION)
            .remove(KEY_CALIBRATED_FINGERPRINT)
            .remove(KEY_CALIBRATED_ANDROID_BUILD_ID)
            .remove(KEY_CALIBRATED_PACKAGE)
            .remove(KEY_CALIBRATED_PACKAGE_VERSION)
            .remove(KEY_CALIBRATED_PACKAGE_UID)
            .remove(KEY_CALIBRATED_PACKAGE_LAST_UPDATE_TIME)
            .remove(KEY_CALIBRATED_PACKAGE_SIGNING_CERT_SHA256)
            .remove(KEY_CALIBRATED_CONTROLLER)
            .remove(KEY_CALIBRATED_APP_BUILD_ID)
            .remove(KEY_CALIBRATED_ANDROID_USER_ID)
            .remove(KEY_CALIBRATED_AT)
            .commit()
            .also { check(it) { "声学校准撤销持久化失败" } }
        invalidateCalibrationIdentityCache()
        Unit
    }

    fun isAcousticCalibrationValid(): Boolean = synchronized(CALIBRATION_IDENTITY_LOCK) {
        val current = currentAcousticCalibrationIdentity() ?: return false
        storedAcousticCalibrationIdentity() == current
    }

    fun calibratedAtEpochMs(): Long? =
        preferences.getLong(KEY_CALIBRATED_AT, 0L).takeIf { it > 0L }

    /**
     * Captures only the system control environment. Third-party app installation, permissions,
     * signatures and updates cannot invalidate or prevent system microphone control.
     */
    fun currentAcousticCalibrationIdentity(): AcousticCalibrationIdentity? =
        synchronized(CALIBRATION_IDENTITY_LOCK) {
            val cachedAt = calibrationIdentityCachedAtNanos
            if (
                cachedAt != null &&
                System.nanoTime() - cachedAt < CALIBRATION_IDENTITY_CACHE_MS * 1_000_000L
            ) {
                return@synchronized calibrationIdentityCache
            }
            val computed = runCatching {
                AcousticCalibrationIdentity(
                    settingsGeneration = calibrationSettingsGeneration,
                    controllerId = controllerId,
                    micBridgeBuildId = BuildConfig.MICBRIDGE_BUILD_ID,
                    androidBuildId = Build.ID,
                    androidFingerprint = Build.FINGERPRINT,
                    androidUserId = appUserId(),
                )
            }.getOrNull()
            calibrationIdentityCache = computed
            calibrationIdentityCachedAtNanos = System.nanoTime()
            computed
        }

    /**
     * Drops the memoized identity so the next capture is fresh. Called from every path in this
     * class that can change any field the identity is derived from; a superfluous call is always
     * safe because it only forces one extra recomputation.
     */
    private fun invalidateCalibrationIdentityCache() {
        synchronized(CALIBRATION_IDENTITY_LOCK) {
            calibrationIdentityCache = null
            calibrationIdentityCachedAtNanos = null
        }
    }

    private fun storedAcousticCalibrationIdentity(): AcousticCalibrationIdentity? {
        if (
            preferences.getString(KEY_CALIBRATED_SCOPE, null) != SYSTEM_CALIBRATION_SCOPE ||
            !preferences.contains(KEY_CALIBRATED_SETTINGS_GENERATION) ||
            !preferences.contains(KEY_CALIBRATED_FINGERPRINT) ||
            !preferences.contains(KEY_CALIBRATED_ANDROID_BUILD_ID) ||
            !preferences.contains(KEY_CALIBRATED_CONTROLLER) ||
            !preferences.contains(KEY_CALIBRATED_APP_BUILD_ID) ||
            !preferences.contains(KEY_CALIBRATED_ANDROID_USER_ID)
        ) return null
        return AcousticCalibrationIdentity(
            settingsGeneration = preferences.getLong(
                KEY_CALIBRATED_SETTINGS_GENERATION,
                -1L,
            ),
            controllerId = preferences.getString(KEY_CALIBRATED_CONTROLLER, null)
                ?: return null,
            micBridgeBuildId = preferences.getString(KEY_CALIBRATED_APP_BUILD_ID, null)
                ?: return null,
            androidBuildId = preferences.getString(KEY_CALIBRATED_ANDROID_BUILD_ID, null)
                ?: return null,
            androidFingerprint = preferences.getString(KEY_CALIBRATED_FINGERPRINT, null)
                ?: return null,
            androidUserId = preferences.getInt(KEY_CALIBRATED_ANDROID_USER_ID, -1),
        )
    }

    private fun generateToken(): String {
        return AuthGuard.generateToken()
    }

    private fun appUserId(): Int = Process.myUid() / PER_USER_RANGE

    private val calibrationSettingsGeneration: Long
        get() = preferences.getLong(KEY_CALIBRATION_SETTINGS_GENERATION, 1L)

    companion object {
        const val CONTROLLER_APP_OPS = "root_appops"
        const val CONTROLLER_AUDIO_MANAGER = "audio_manager"
        const val CONTROLLER_SENSOR_PRIVACY = "root_sensor_privacy"
        const val GLOBAL_MIC_TARGET = "android-global-microphone"
        // Used only when reading old experimental AppOps metadata.
        const val DEFAULT_CHATGPT_PACKAGE = "com.openai.chatgpt"
        const val DEFAULT_PORT = 8787
        const val DEFAULT_MAX_OPEN_SECONDS = 30
        const val MIN_OPEN_SECONDS = 5
        const val MAX_OPEN_SECONDS = 30

        private val RELEASE_CONTROLLERS = setOf(
            CONTROLLER_AUDIO_MANAGER,
            CONTROLLER_SENSOR_PRIVACY,
        )

        private const val PREFERENCES = "micbridge_settings"
        private const val KEY_TOKEN = "token"
        private const val KEY_TOKEN_GENERATION = "token_generation"
        private const val KEY_CONTROLLER = "controller"
        private const val KEY_TARGET_PACKAGE = "target_package"
        private const val KEY_CALIBRATION_SETTINGS_GENERATION =
            "calibration_settings_generation"
        private const val KEY_ORIGINAL_APPOPS_MODE = "original_appops_mode"
        private const val KEY_ORIGINAL_APPOPS_PACKAGE = "original_appops_package"
        private const val KEY_ORIGINAL_APPOPS_USER = "original_appops_user"
        private const val KEY_OWNED_APPOPS_IGNORE_MODE = "owned_appops_ignore_mode"
        private const val KEY_OWNED_APPOPS_IGNORE_PACKAGE = "owned_appops_ignore_package"
        private const val KEY_OWNED_APPOPS_IGNORE_USER = "owned_appops_ignore_user"
        private const val KEY_PORT = "port"
        private const val KEY_MAX_OPEN_SECONDS = "max_open_seconds"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_RELIABLE_MODE = "reliable_mode"
        private const val KEY_ROOT_BOOT_GUARD = "root_boot_guard_installed"
        private const val KEY_CALIBRATED_SCOPE = "calibrated_scope"
        private const val SYSTEM_CALIBRATION_SCOPE = "system_microphone_v1"
        private const val KEY_CALIBRATED_FINGERPRINT = "calibrated_fingerprint"
        private const val KEY_CALIBRATED_SETTINGS_GENERATION =
            "calibrated_settings_generation"
        private const val KEY_CALIBRATED_ANDROID_BUILD_ID = "calibrated_android_build_id"
        private const val KEY_CALIBRATED_PACKAGE = "calibrated_package"
        private const val KEY_CALIBRATED_PACKAGE_VERSION = "calibrated_package_version"
        private const val KEY_CALIBRATED_PACKAGE_UID = "calibrated_package_uid"
        private const val KEY_CALIBRATED_PACKAGE_LAST_UPDATE_TIME =
            "calibrated_package_last_update_time"
        private const val KEY_CALIBRATED_PACKAGE_SIGNING_CERT_SHA256 =
            "calibrated_package_signing_cert_sha256"
        private const val KEY_CALIBRATED_CONTROLLER = "calibrated_controller"
        private const val KEY_CALIBRATED_APP_BUILD_ID = "calibrated_app_build_id"
        private const val KEY_CALIBRATED_ANDROID_USER_ID = "calibrated_android_user_id"
        private const val KEY_CALIBRATED_AT = "calibrated_at"
        private const val PER_USER_RANGE = 100_000
        private val RESTORABLE_APPOPS_MODES = setOf("allow", "default")
        private val CALIBRATION_IDENTITY_LOCK = Any()

        /** Maximum age of a memoized system identity; local controller changes invalidate it. */
        private const val CALIBRATION_IDENTITY_CACHE_MS = 250L
    }
}
