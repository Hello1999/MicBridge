package com.jack.micbridge.capturehook;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRouting;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Keeps ChatGPT's capture on the phone's built-in mic while its voice plays in Bluetooth earbuds.
 *
 * Preferring the built-in mic on the AudioRecord alone is not enough: as soon as ChatGPT calls
 * startBluetoothSco(), the policy moves every VOICE_COMMUNICATION capture onto the SCO mic,
 * overriding the recorder's preferred device. So SCO is refused inside ChatGPT, and so are
 * communication mode, other communication devices and speakerphone: with SCO refused, each of
 * them would put voice on the earpiece/speaker instead of the A2DP earbuds.
 *
 * Changes routing only: never starts, reads, unmutes or retains a recorder on its own, and does
 * not touch the system-wide mute that MicBridge drives.
 */
public final class PhoneMicHook implements IXposedHookLoadPackage {
    private static final String TARGET = "com.openai.chatgpt";
    private static final String TAG = "MicBridgeCapture";
    private final Set<AudioRecord> observed = Collections.newSetFromMap(new WeakHashMap<>());
    private final AudioRouting.OnRoutingChangedListener routingListener = router -> {
        if (router instanceof AudioRecord) report((AudioRecord) router, "route_changed");
    };

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam pkg) {
        // Enforce scope even if someone selects additional packages in LSPosed.
        if (!TARGET.equals(pkg.packageName)) return;
        hookRecorder();
        hookAudioManager();
        Log.i(TAG, "loaded process=" + pkg.processName);
        XposedBridge.log(TAG + " loaded process=" + pkg.processName);
    }

    private void hookRecorder() {
        XposedHelpers.findAndHookMethod(AudioRecord.class, "setPreferredDevice",
                AudioDeviceInfo.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            AudioDeviceInfo mic = builtInMic();
                            if (mic != null) param.args[0] = mic;
                        } catch (Throwable error) { failure("set_preference", error); }
                    }
                });
        XposedBridge.hookAllMethods(AudioRecord.class, "startRecording", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                AudioRecord recorder = (AudioRecord) param.thisObject;
                try {
                    AudioDeviceInfo mic = builtInMic();
                    if (mic == null) { Log.w(TAG, "builtin_missing"); return; }
                    boolean accepted = recorder.setPreferredDevice(mic);
                    Log.i(TAG, "request session=" + recorder.getAudioSessionId()
                            + " input=" + describe(mic) + " accepted=" + accepted);
                    synchronized (observed) {
                        if (observed.add(recorder)) {
                            recorder.addOnRoutingChangedListener(routingListener,
                                    new Handler(Looper.getMainLooper()));
                        }
                    }
                } catch (Throwable error) { failure("before_start", error); }
            }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!param.hasThrowable()) report((AudioRecord) param.thisObject, "started");
            }
        });
        XposedBridge.hookAllMethods(AudioRecord.class, "release", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                AudioRecord recorder = (AudioRecord) param.thisObject;
                try {
                    synchronized (observed) {
                        if (observed.remove(recorder)) {
                            recorder.removeOnRoutingChangedListener(routingListener);
                        }
                    }
                } catch (Throwable error) { failure("release_listener", error); }
            }
        });
    }

    private static void hookAudioManager() {
        XposedHelpers.findAndHookMethod(AudioManager.class, "setMode", int.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                int mode = (int) param.args[0];
                boolean block = mode == AudioManager.MODE_IN_COMMUNICATION;
                if (block) param.setResult(null);
                Log.i(TAG, "setMode(" + mode + ")" + (block ? " blocked" : ""));
            }
        });
        XposedHelpers.findAndHookMethod(AudioManager.class, "startBluetoothSco", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(null);
                Log.i(TAG, "startBluetoothSco() blocked");
            }
        });
        XposedHelpers.findAndHookMethod(AudioManager.class, "setBluetoothScoOn", boolean.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                boolean on = (boolean) param.args[0];
                if (on) param.setResult(null);
                Log.i(TAG, "setBluetoothScoOn(" + on + ")" + (on ? " blocked" : ""));
            }
        });
        XposedHelpers.findAndHookMethod(AudioManager.class, "setCommunicationDevice",
                AudioDeviceInfo.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        // Any communication device pins voice off A2DP: SCO takes the mic, and
                        // ChatGPT's fallback after SCO fails is the speaker. Refuse them all so
                        // voice follows the media route. Report success for non-SCO devices so
                        // ChatGPT does not keep retrying.
                        AudioDeviceInfo device = (AudioDeviceInfo) param.args[0];
                        param.setResult(device != null && !isHeadsetVoiceLink(device.getType()));
                        Log.i(TAG, "setCommunicationDevice(" + describe(device) + ") blocked");
                    }
                });
        XposedHelpers.findAndHookMethod(AudioManager.class, "setSpeakerphoneOn", boolean.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                boolean on = (boolean) param.args[0];
                if (on) param.setResult(null);
                Log.i(TAG, "setSpeakerphoneOn(" + on + ")" + (on ? " blocked" : ""));
            }
        });
        // Logged only, to see how ChatGPT reacts to the refusals above.
        for (String name : new String[] {"stopBluetoothSco", "clearCommunicationDevice"}) {
            XposedBridge.hookAllMethods(AudioManager.class, name, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Log.i(TAG, name + (param.args.length > 0 ? "(" + param.args[0] + ")" : "()"));
                }
            });
        }
    }

    private static boolean isHeadsetVoiceLink(int type) {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                || type == AudioDeviceInfo.TYPE_BLE_SPEAKER;
    }

    private static AudioDeviceInfo builtInMic() {
        Application app = AndroidAppHelper.currentApplication();
        if (app == null) return null;
        AudioManager manager = app.getSystemService(AudioManager.class);
        if (manager == null) return null;
        AudioDeviceInfo fallback = null;
        for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (device.getType() != AudioDeviceInfo.TYPE_BUILTIN_MIC) continue;
            if ("bottom".equals(device.getAddress())) return device;
            if (fallback == null) fallback = device;
        }
        return fallback;
    }

    private static void report(AudioRecord recorder, String event) {
        try {
            Log.i(TAG, event + " session=" + recorder.getAudioSessionId()
                    + " state=" + recorder.getRecordingState()
                    + " preferred=" + describe(recorder.getPreferredDevice())
                    + " routed=" + describe(recorder.getRoutedDevice()));
        } catch (Throwable error) { failure("report", error); }
    }

    private static String describe(AudioDeviceInfo device) {
        return device == null ? "none" : "id:" + device.getId() + ",type:" + device.getType();
    }

    private static void failure(String stage, Throwable error) {
        Log.w(TAG, stage + " failed: " + error.getClass().getSimpleName());
    }
}
