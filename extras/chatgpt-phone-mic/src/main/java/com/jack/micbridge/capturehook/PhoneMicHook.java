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

/** Changes routing only: never starts, reads, unmutes or retains a recorder on its own. */
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
        Log.i(TAG, "loaded process=" + pkg.processName);
        XposedBridge.log(TAG + " loaded process=" + pkg.processName);
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
