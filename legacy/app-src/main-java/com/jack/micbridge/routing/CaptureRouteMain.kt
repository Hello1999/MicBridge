package com.jack.micbridge.routing

import android.media.AudioDeviceInfo
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.system.exitProcess

/**
 * Root-side helper that reads and writes the system's *preferred capture device per capture preset*.
 *
 * ## Why a separate process
 * `AudioManager.setPreferredDeviceForCapturePreset` (API 31+) is guarded by the signature-level
 * permission `MODIFY_AUDIO_ROUTING`, which a normal application can never hold. The permission check
 * happens inside `system_server` against `Binder.getCallingUid()`, and the framework grants every
 * permission to app id 0, so a process that is genuinely running as root passes it. MicBridge
 * therefore does not try to call the API from its own uid; it launches this class through the
 * existing root shell:
 *
 * ```
 * su -c "CLASSPATH=/data/app/~~…/base.apk exec app_process /system/bin \
 *     com.jack.micbridge.routing.CaptureRouteMain set 1,6,7 builtin_mic"
 * ```
 *
 * `app_process` starts a bare ART VM with this APK on the classpath and invokes [main]. There is no
 * `Application`, no `Context` and no `Looper`, so this file deliberately uses plain JVM-style code
 * and talks to `IAudioService` over Binder directly.
 *
 * ## Safety
 * Capture-preset routing only selects *which* input device an already-permitted recorder is fed
 * from. It cannot open, keep open, or delay the microphone, and no code path here is reachable from
 * MicBridge's fail-closed BLOCK sequence. This class is a standalone CLI: nothing in the service,
 * coordinator, settings or UI calls it yet.
 *
 * ## Command line
 * ```
 * get   <preset>[,<preset>…]                 -> preset=<n> devices=<type>[,<type>…]
 * set   <preset>[,<preset>…] builtin_mic     -> preset=<n> status=<int> devices=<readback>
 * clear <preset>[,<preset>…]                 -> preset=<n> status=<int> devices=<readback>
 * ```
 * Exit codes: 0 success, 2 usage error (`error=usage` on stderr), 3 readback did not verify,
 * 4 reflection or binder failure (`error=<Exception>: <message>` on stderr). Nothing but the
 * `preset=` lines is ever written to stdout, and they are only written once the whole run
 * succeeded.
 */
object CaptureRouteMain {

    private const val EXIT_OK = 0
    private const val EXIT_USAGE = 2
    private const val EXIT_UNVERIFIED = 3
    private const val EXIT_FAILURE = 4

    private const val MIN_PRESET = 0
    private const val MAX_PRESET = 10

    /** `AudioSystem.SUCCESS`. */
    private const val STATUS_SUCCESS = 0

    /** `AudioDeviceAttributes.ROLE_INPUT`; read from the class when possible. */
    private const val ROLE_INPUT_FALLBACK = 1

    private const val CLASS_AUDIO_DEVICE_ATTRIBUTES = "android.media.AudioDeviceAttributes"
    private const val CLASS_I_AUDIO_SERVICE = "android.media.IAudioService"
    private const val CLASS_I_AUDIO_SERVICE_STUB = "android.media.IAudioService\$Stub"
    private const val CLASS_I_BINDER = "android.os.IBinder"
    private const val CLASS_SERVICE_MANAGER = "android.os.ServiceManager"
    private const val CLASS_VM_RUNTIME = "dalvik.system.VMRuntime"

    @JvmStatic
    fun main(args: Array<String>) {
        exemptHiddenApis()
        val exitCode = try {
            val lines = mutableListOf<String>()
            val code = dispatch(args, lines)
            lines.forEach(::println)
            System.out.flush()
            code
        } catch (usage: UsageException) {
            System.err.println("error=usage")
            System.err.flush()
            EXIT_USAGE
        } catch (failure: Throwable) {
            System.err.println("error=" + describe(failure))
            System.err.flush()
            EXIT_FAILURE
        }
        exitProcess(exitCode)
    }

    /**
     * Lifts hidden-API restrictions through the meta-reflection escape hatch: `Class.forName` and
     * `Class.getDeclaredMethod` are themselves reached reflectively, so the greylist check sees the
     * platform as the caller. A process started by `app_process` outside the zygote usually has no
     * hidden-API enforcement at all, so failure here is not fatal and the run continues.
     */
    private fun exemptHiddenApis() {
        runCatching {
            val classClass = Class::class.java
            val forName = classClass.getDeclaredMethod("forName", String::class.java)
            val getDeclaredMethod = classClass.getDeclaredMethod(
                "getDeclaredMethod",
                String::class.java,
                emptyArray<Class<*>>().javaClass,
            )
            val vmRuntime = forName.invoke(null, CLASS_VM_RUNTIME) as Class<*>
            val getRuntime = getDeclaredMethod.invoke(
                vmRuntime,
                "getRuntime",
                null,
            ) as Method
            val setHiddenApiExemptions = getDeclaredMethod.invoke(
                vmRuntime,
                "setHiddenApiExemptions",
                arrayOf<Class<*>>(emptyArray<String>().javaClass),
            ) as Method
            val runtime = getRuntime.invoke(null)
            setHiddenApiExemptions.invoke(runtime, arrayOf("L"))
        }
    }

    private fun dispatch(args: Array<String>, out: MutableList<String>): Int {
        if (args.size < 2) throw UsageException()
        val presets = parsePresets(args[1])
        return when (args[0]) {
            "get" -> {
                if (args.size != 2) throw UsageException()
                get(presets, out)
            }

            "set" -> {
                if (args.size != 3 || args[2] != "builtin_mic") throw UsageException()
                set(presets, out)
            }

            "clear" -> {
                if (args.size != 2) throw UsageException()
                clear(presets, out)
            }

            else -> throw UsageException()
        }
    }

    private fun parsePresets(argument: String): List<Int> {
        val tokens = argument.split(',')
        if (tokens.isEmpty()) throw UsageException()
        return tokens.map { token ->
            val preset = token.toIntOrNull() ?: throw UsageException()
            if (preset < MIN_PRESET || preset > MAX_PRESET) throw UsageException()
            preset
        }
    }

    private fun get(presets: List<Int>, out: MutableList<String>): Int {
        val service = AudioService()
        presets.forEach { preset ->
            out += "preset=$preset devices=" + service.preferredDevices(preset).joinToString(",")
        }
        return EXIT_OK
    }

    private fun set(presets: List<Int>, out: MutableList<String>): Int {
        val service = AudioService()
        val builtInMic = service.builtInMicAttributes()
        var verified = true
        presets.forEach { preset ->
            val status = service.setPreferredDevices(preset, listOf(builtInMic))
            val readback = service.preferredDevices(preset)
            out += "preset=$preset status=$status devices=" + readback.joinToString(",")
            if (status != STATUS_SUCCESS ||
                readback != listOf(AudioDeviceInfo.TYPE_BUILTIN_MIC)
            ) {
                verified = false
            }
        }
        return if (verified) EXIT_OK else EXIT_UNVERIFIED
    }

    private fun clear(presets: List<Int>, out: MutableList<String>): Int {
        val service = AudioService()
        var verified = true
        presets.forEach { preset ->
            val status = service.clearPreferredDevices(preset)
            val readback = service.preferredDevices(preset)
            out += "preset=$preset status=$status devices=" + readback.joinToString(",")
            if (status != STATUS_SUCCESS || readback.isNotEmpty()) verified = false
        }
        return if (verified) EXIT_OK else EXIT_UNVERIFIED
    }

    /**
     * Reflective handle on `IAudioService`. Every class and method here is hidden platform API, so
     * none of it can be referenced statically; the methods are resolved on the `IAudioService`
     * interface itself, which the `IAudioService$Stub$Proxy` returned by `asInterface` implements.
     */
    private class AudioService {
        private val serviceInterface: Class<*> = Class.forName(CLASS_I_AUDIO_SERVICE)
        private val service: Any = connect()

        private val getPreferredDevicesForCapturePreset: Method = serviceInterface.getMethod(
            "getPreferredDevicesForCapturePreset",
            Int::class.javaPrimitiveType,
        )
        private val setPreferredDevicesForCapturePreset: Method = serviceInterface.getMethod(
            "setPreferredDevicesForCapturePreset",
            Int::class.javaPrimitiveType,
            List::class.java,
        )
        private val clearPreferredDevicesForCapturePreset: Method = serviceInterface.getMethod(
            "clearPreferredDevicesForCapturePreset",
            Int::class.javaPrimitiveType,
        )

        private fun connect(): Any {
            val serviceManager = Class.forName(CLASS_SERVICE_MANAGER)
            val getService = serviceManager.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "audio")
                ?: throw IllegalStateException("audio service binder is null")
            val stub = Class.forName(CLASS_I_AUDIO_SERVICE_STUB)
            val asInterface = stub.getMethod("asInterface", Class.forName(CLASS_I_BINDER))
            return asInterface.invoke(null, binder)
                ?: throw IllegalStateException("IAudioService proxy is null")
        }

        /**
         * `AudioDeviceAttributes(ROLE_INPUT, TYPE_BUILTIN_MIC, "")`. The constructor is public but
         * the class is `@SystemApi`, so it is absent from the public `android.jar` and has to be
         * instantiated reflectively even though no reflection is needed at runtime semantics level.
         */
        fun builtInMicAttributes(): Any {
            val attributes = Class.forName(CLASS_AUDIO_DEVICE_ATTRIBUTES)
            val roleInput = runCatching {
                attributes.getField("ROLE_INPUT").getInt(null)
            }.getOrDefault(ROLE_INPUT_FALLBACK)
            val constructor = attributes.getConstructor(
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
            )
            return constructor.newInstance(roleInput, AudioDeviceInfo.TYPE_BUILTIN_MIC, "")
        }

        fun preferredDevices(preset: Int): List<Int> {
            val devices = getPreferredDevicesForCapturePreset.invoke(service, preset)
            return deviceTypes(devices)
        }

        fun setPreferredDevices(preset: Int, devices: List<Any>): Int =
            setPreferredDevicesForCapturePreset.invoke(service, preset, devices) as Int

        fun clearPreferredDevices(preset: Int): Int =
            clearPreferredDevicesForCapturePreset.invoke(service, preset) as Int

        private fun deviceTypes(devices: Any?): List<Int> {
            val list = devices as? List<*> ?: return emptyList()
            return list.filterNotNull().map { attributes ->
                attributes.javaClass.getMethod("getType").invoke(attributes) as Int
            }
        }
    }

    private class UsageException : Exception()

    private fun describe(failure: Throwable): String {
        val cause = if (failure is InvocationTargetException) {
            failure.targetException ?: failure
        } else {
            failure
        }
        val message = cause.message.orEmpty()
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
        return cause.javaClass.simpleName + ": " + message
    }
}
