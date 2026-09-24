package com.jack.micbridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jack.micbridge.LinkState
import com.jack.micbridge.MainActivity
import com.jack.micbridge.MuteMethod
import com.jack.micbridge.Prefs
import com.jack.micbridge.PttInputs
import com.jack.micbridge.PttPolicy
import com.jack.micbridge.PttRuntime
import com.jack.micbridge.PttStatus
import com.jack.micbridge.R
import com.jack.micbridge.ble.ButtonLink
import com.jack.micbridge.ble.ButtonProtocol
import com.jack.micbridge.mic.MicGate
import com.jack.micbridge.mic.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Push-to-talk loop: while running, the system mic is muted unless the ESP32 button (or the
 * on-screen button) is held. Stopping the service restores the microphone.
 *
 * Threads: button/screen inputs are folded on [stateThread]; the mute gate is driven on
 * [gateThread] from the conflated [desiredOpen] flow, so a slow root call never queues stale
 * toggles.
 */
class PttService : Service() {
    private val stateThread = Executors.newSingleThreadExecutor { Thread(it, "ptt-state") }.asCoroutineDispatcher()
    private val gateExecutor = Executors.newSingleThreadExecutor { Thread(it, "ptt-gate") }
    private val gateThread = gateExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob())

    private lateinit var audioManager: AudioManager
    private var gate: MicGate? = null
    private var link: ButtonLink? = null
    private var muteReceiver: BroadcastReceiver? = null
    private var started = false

    private var inputs = PttInputs() // stateThread only
    private val desiredOpen = MutableStateFlow(false)
    @Volatile private var desiredChangedAtNs = 0L
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_STICKY
        started = true

        val prefs = Prefs(this)
        val address = prefs.deviceAddress
        PttRuntime.status.value = PttStatus(
            running = true,
            link = if (address == null) LinkState.NO_DEVICE else LinkState.CONNECTING,
        )
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val gate = MicGate.create(prefs.method, audioManager).also { gate = it }
        scope.launch(gateThread) { desiredOpen.collect { apply(gate, it) } }
        scope.launch(stateThread) {
            PttRuntime.screenPressed.collect { pressed ->
                inputs = inputs.copy(screenPressed = pressed)
                reevaluate()
            }
        }
        // Heartbeat watchdog: a held button that stops talking counts as released.
        scope.launch(stateThread) {
            while (isActive) {
                delay(200)
                reevaluate()
            }
        }
        if (gate.method == MuteMethod.AUDIO_MANAGER) watchExternalUnmute(gate)

        if (address != null) {
            link = ButtonLink(this, address, linkListener).also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        scope.cancel()
        link?.close()
        muteReceiver?.let { unregisterReceiver(it) }
        gate?.let { g ->
            // Leave the phone usable: a stopped push-to-talk must not strand the mic muted.
            // Restore on the gate thread so it lands after any in-flight apply/enforce.
            val restored = runCatching {
                gateExecutor.submit<Boolean> { g.setOpen(true) }.get(5, TimeUnit.SECONDS)
            }.getOrDefault(false)
            g.close()
            PttRuntime.status.value = PttStatus(
                micOpen = g.isOpen(),
                error = if (restored) null else "恢复麦克风失败，请点“恢复麦克风”重试",
            )
        } ?: run { PttRuntime.status.value = PttStatus() }
        PttRuntime.screenPressed.value = false
        stateThread.close()
        gateThread.close()
        super.onDestroy()
    }

    private val linkListener = object : ButtonLink.Listener {
        override fun onReady(ready: Boolean) {
            scope.launch(stateThread) {
                inputs = inputs.copy(linkReady = ready, blePressed = ready && inputs.blePressed)
                PttRuntime.status.update {
                    it.copy(link = if (ready) LinkState.CONNECTED else LinkState.CONNECTING, error = if (ready) null else it.error)
                }
                reevaluate()
                notifyStatus()
            }
        }

        override fun onPacket(packet: ButtonProtocol.Packet) {
            val receivedAt = SystemClock.elapsedRealtime()
            scope.launch(stateThread) {
                Log.d(TAG, "packet pressed=${packet.pressed} seq=${packet.seq} gap=${receivedAt - inputs.lastPacketAtMs}ms")
                inputs = inputs.copy(blePressed = packet.pressed, lastPacketAtMs = receivedAt)
                reevaluate()
            }
        }

        override fun onProblem(message: String) {
            PttRuntime.status.update { it.copy(error = message) }
        }
    }

    private fun reevaluate() {
        val now = SystemClock.elapsedRealtime()
        val open = PttPolicy.micShouldBeOpen(inputs, now)
        val pressed = PttPolicy.blePressed(inputs, now)
        if (desiredOpen.value != open) {
            desiredChangedAtNs = System.nanoTime()
            desiredOpen.value = open
        }
        if (PttRuntime.status.value.buttonPressed != pressed) {
            PttRuntime.status.update { it.copy(buttonPressed = pressed) }
        }
    }

    private fun apply(gate: MicGate, open: Boolean) {
        if (stopping) return
        val ok = gate.setOpen(open)
        val latencyMs = (System.nanoTime() - desiredChangedAtNs) / 1_000_000
        Log.i(TAG, "mic ${if (open) "OPEN" else "MUTED"} ok=$ok" + if (desiredChangedAtNs != 0L) " latency=${latencyMs}ms" else "")
        PttRuntime.status.update {
            it.copy(
                micOpen = gate.isOpen(),
                lastLatencyMs = latencyMs.takeIf { desiredChangedAtNs != 0L },
                error = if (ok) it.error?.takeUnless { e -> e.startsWith(GATE_ERROR) }
                else "$GATE_ERROR：${if (gate.method == MuteMethod.SENSOR_PRIVACY) "Root 未授权或命令失败" else "系统拒绝"}",
            )
        }
        notifyStatus()
    }

    /** ChatGPT or the system may unmute on its own; put the desired state straight back. */
    private fun watchExternalUnmute(gate: MicGate) {
        val enforce = {
            scope.launch(gateThread) {
                val want = desiredOpen.value
                if (!stopping && gate.isOpen() != want) {
                    Log.w(TAG, "mic mute changed externally; re-applying open=$want")
                    gate.setOpen(want)
                    PttRuntime.status.update { it.copy(micOpen = gate.isOpen()) }
                }
            }
        }
        muteReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                enforce()
            }
        }.also {
            ContextCompat.registerReceiver(
                this, it, IntentFilter(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
        scope.launch(gateThread) {
            while (isActive) {
                delay(1_000)
                enforce()
            }
        }
    }

    private fun enterForeground(): Boolean = runCatching {
        ensureChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }.onFailure {
        Log.e(TAG, "startForeground failed", it)
        PttRuntime.status.value = PttStatus(error = "无法启动后台服务：请授予蓝牙“附近设备”权限")
    }.isSuccess

    private fun notifyStatus() {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val s = PttRuntime.status.value
        val mic = when (s.micOpen) {
            true -> "说话中"
            false -> "已静音"
            null -> "麦克风状态未知"
        }
        val button = when (s.link) {
            LinkState.CONNECTED -> "按钮已连接"
            LinkState.CONNECTING -> "正在连接按钮"
            LinkState.NO_DEVICE -> "未绑定按钮"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PttService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_micbridge)
            .setContentTitle(mic)
            .setContentText(button)
            .setContentIntent(open)
            .addAction(0, "停止并恢复麦克风", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private fun ensureChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "按键说话", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val TAG = "MicBridgePtt"
        private const val CHANNEL_ID = "ptt"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.jack.micbridge.STOP"
        private const val GATE_ERROR = "静音控制失败"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PttService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PttService::class.java))
        }

        /**
         * Manual recovery when the service is not running (e.g. after a crash left the mic
         * muted). Clears the AudioManager mute and, for the root method, the privacy toggle.
         */
        fun restoreMic(context: Context, method: MuteMethod, done: (Boolean) -> Unit) {
            val appContext = context.applicationContext
            thread(name = "mic-restore") {
                val am = appContext.getSystemService(AudioManager::class.java)
                var ok = runCatching { am.isMicrophoneMute = false; !am.isMicrophoneMute }.getOrDefault(false)
                if (method == MuteMethod.SENSOR_PRIVACY) {
                    val shell = RootShell()
                    val user = android.os.Process.myUid() / 100_000
                    ok = ok && shell.run("cmd sensor_privacy disable $user microphone") == 0
                    shell.close()
                }
                PttRuntime.status.update { it.copy(micOpen = if (ok) true else it.micOpen) }
                done(ok)
            }
        }
    }
}
