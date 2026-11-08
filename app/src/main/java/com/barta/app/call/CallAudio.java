package com.barta.app.call;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * Sound for calls: ringtone (incoming), ring-back tone (outgoing), and switching
 * between earpiece and loudspeaker during the call.
 */
public class CallAudio {
    private final Context ctx;
    private final AudioManager am;
    private Ringtone ringtone;
    private ToneGenerator ringback;
    private Vibrator vibrator;
    private int previousMode = AudioManager.MODE_NORMAL;
    private boolean inCallMode = false;

    public CallAudio(Context context) {
        ctx = context.getApplicationContext();
        am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
    }

    /** Phone ringtone + vibration for an incoming call. */
    public void startRinging() {
        stopRinging();
        try {
            ringtone = RingtoneManager.getRingtone(ctx,
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));
            if (ringtone != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ringtone.setLooping(true);
                ringtone.play();
            }
        } catch (Exception ignored) { }
        vibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            long[] pattern = {0, 800, 1000};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
            } else {
                vibrator.vibrate(pattern, 0);
            }
        }
    }

    /** "tuuut... tuuut..." while the other phone is ringing. */
    public void startRingback() {
        stopRinging();
        try {
            ringback = new ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70);
            ringback.startTone(ToneGenerator.TONE_SUP_RINGTONE);
        } catch (RuntimeException ignored) {
            ringback = null;
        }
    }

    public void stopRinging() {
        if (ringtone != null) {
            try {
                ringtone.stop();
            } catch (Exception ignored) { }
            ringtone = null;
        }
        if (ringback != null) {
            ringback.stopTone();
            ringback.release();
            ringback = null;
        }
        if (vibrator != null) {
            vibrator.cancel();
            vibrator = null;
        }
    }

    /** Call audio mode. Video calls start on the loudspeaker, voice calls on the earpiece. */
    public void startCall(boolean speaker) {
        if (am == null) return;
        if (!inCallMode) {
            previousMode = am.getMode();
            inCallMode = true;
        }
        am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        setSpeaker(speaker);
    }

    public void setSpeaker(boolean on) {
        if (am == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            int wanted = on ? AudioDeviceInfo.TYPE_BUILTIN_SPEAKER : AudioDeviceInfo.TYPE_BUILTIN_EARPIECE;
            for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                if (d.getType() == wanted) {
                    am.setCommunicationDevice(d);
                    return;
                }
            }
            if (!on) am.clearCommunicationDevice();
        } else {
            //noinspection deprecation
            am.setSpeakerphoneOn(on);
        }
    }

    public void release() {
        stopRinging();
        if (am == null || !inCallMode) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.clearCommunicationDevice();
        } else {
            //noinspection deprecation
            am.setSpeakerphoneOn(false);
        }
        am.setMode(previousMode);
        inCallMode = false;
    }
}
