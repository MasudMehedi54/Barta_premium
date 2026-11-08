package com.barta.app.ui;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.barta.app.call.CallAudio;
import com.barta.app.call.CallConfig;
import com.barta.app.call.CallSignaling;
import com.barta.app.call.IncomingCallNotifier;
import com.barta.app.call.WebRtcClient;
import com.barta.app.data.UsersLiveData;
import com.barta.app.databinding.ActivityCallBinding;
import com.barta.app.model.User;
import com.barta.app.service.CallService;
import com.barta.app.util.ChatUtil;
import com.barta.app.util.MessageSender;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.firestore.FirebaseFirestore;

import org.webrtc.IceCandidate;
import org.webrtc.RendererCommon;
import org.webrtc.SessionDescription;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Voice / video call screen (1-to-1).
 *
 * Caller: write callData + calls/{callee} -> send offer -> wait for answer -> connected.
 * Callee: ring -> Answer -> read offer -> send answer -> connected.
 * Both phones also exchange "ICE candidates" (network addresses). Firebase carries only these
 * short texts; the voice and video travel directly between the phones with WebRTC.
 */
public class CallActivity extends AppCompatActivity implements WebRtcClient.Listener {
    private static final String EX_OUTGOING = "outgoing";
    private static final String EX_PEER_UID = "peerUid";
    private static final String EX_PEER_NAME = "peerName";
    private static final String EX_VIDEO = "video";
    private static final String EX_CALL_ID = "callId";
    private static final String EX_AUTO_ACCEPT = "autoAccept";
    private static final long CONNECT_TIMEOUT_MS = 30_000;

    /** True while a call screen exists. A second incoming call gets "busy". */
    public static volatile boolean active = false;

    private ActivityCallBinding b;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private CallAudio audio;
    private WebRtcClient rtc;

    private String me, myName, peerUid, peerName, callId;
    private boolean caller, video;
    private boolean accepted, connected, ended, released;
    private boolean cameraAllowed, muted, speakerOn, cameraOff, frontCamera = true;
    private boolean localInit, remoteInit, answerApplied, offerApplied;
    private long connectedAt = 0;
    /** Last status written by the OTHER phone (declined / busy / ...), used for the call log. */
    private String peerStatus;
    private VideoTrack remoteTrack;

    // Realtime Database listeners (removed in release())
    private final List<Runnable> removeListeners = new ArrayList<>();

    private Runnable afterPermissions;
    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), res -> {
                boolean mic = granted(Manifest.permission.RECORD_AUDIO);
                cameraAllowed = video && granted(Manifest.permission.CAMERA);
                if (!mic) {
                    Toast.makeText(this, "Microphone permission is needed for calls", Toast.LENGTH_LONG).show();
                    if (caller) endCall(null, "Call cancelled");
                    else endCall(CallSignaling.DECLINED, "Call declined");
                    return;
                }
                if (video && !cameraAllowed) {
                    Toast.makeText(this, "Camera not allowed: the other person won't see you",
                            Toast.LENGTH_LONG).show();
                }
                Runnable next = afterPermissions;
                afterPermissions = null;
                if (next != null) next.run();
            });

    private final Runnable ringTimeout = () -> {
        if (!accepted && !ended) endCall(CallSignaling.MISSED, "No answer");
    };
    private final Runnable connectTimeout = () -> {
        if (!connected && !ended) endCall(CallSignaling.ENDED, "Couldn't connect");
    };
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (ended || connectedAt == 0) return;
            long s = (System.currentTimeMillis() - connectedAt) / 1000;
            b.tvStatus.setText(String.format(Locale.US, "%02d:%02d", s / 60, s % 60));
            handler.postDelayed(this, 1000);
        }
    };

    // ------------------------------------------------------------------ intents

    public static Intent outgoing(Context ctx, String peerUid, String peerName, boolean video) {
        return new Intent(ctx, CallActivity.class)
                .putExtra(EX_OUTGOING, true)
                .putExtra(EX_PEER_UID, peerUid)
                .putExtra(EX_PEER_NAME, peerName)
                .putExtra(EX_VIDEO, video);
    }

    public static Intent incoming(Context ctx, String callId, String callerUid, String callerName,
                                  boolean video, boolean autoAccept) {
        return new Intent(ctx, CallActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EX_OUTGOING, false)
                .putExtra(EX_CALL_ID, callId)
                .putExtra(EX_PEER_UID, callerUid)
                .putExtra(EX_PEER_NAME, callerName)
                .putExtra(EX_VIDEO, video)
                .putExtra(EX_AUTO_ACCEPT, autoAccept);
    }

    // ------------------------------------------------------------------ setup

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Show over the lock screen and wake the display for incoming calls
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        b = ActivityCallBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        Intent in = getIntent();
        caller = in.getBooleanExtra(EX_OUTGOING, true);
        peerUid = in.getStringExtra(EX_PEER_UID);
        peerName = in.getStringExtra(EX_PEER_NAME);
        video = in.getBooleanExtra(EX_VIDEO, false);
        callId = in.getStringExtra(EX_CALL_ID);
        if (user == null || peerUid == null || (!caller && callId == null) || active) {
            if (active) Toast.makeText(this, "You are already in a call", Toast.LENGTH_SHORT).show();
            released = true;   // nothing to clean up
            finish();
            return;
        }
        active = true;
        me = user.getUid();
        myName = user.getDisplayName() != null ? user.getDisplayName() : user.getEmail();
        if (peerName == null) peerName = "Barta user";
        audio = new CallAudio(this);
        IncomingCallNotifier.cancel(this);

        b.tvName.setText(peerName);
        b.avatar.setName(peerName);
        b.tvKind.setText(video ? "Barta video call" : "Barta voice call");
        UsersLiveData.get().observe(this, users -> {
            User u = users == null ? null : users.get(peerUid);
            if (u != null) b.avatar.setUser(peerName, u.photoBase64);
        });

        // Back = keep the call running and go back to the app (like WhatsApp)
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (ended) finish();
                else moveTaskToBack(true);
            }
        });

        setupButtons();
        if (caller) {
            showActiveControls();
            b.tvStatus.setText("Calling…");
            withPermissions(this::startOutgoing);
        } else {
            showIncomingControls();
            b.tvStatus.setText(video ? "Incoming video call" : "Incoming voice call");
            listenStatus();
            if (in.getBooleanExtra(EX_AUTO_ACCEPT, false)) {
                accept();
            } else {
                audio.startRinging();
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // "Answer" tapped in the notification while this screen was already open
        if (!caller && !accepted && intent.getBooleanExtra(EX_AUTO_ACCEPT, false)
                && callId != null && callId.equals(intent.getStringExtra(EX_CALL_ID))) {
            accept();
        }
    }

    private void setupButtons() {
        b.btnAccept.setOnClickListener(v -> accept());
        b.btnDecline.setOnClickListener(v -> endCall(CallSignaling.DECLINED, "Call declined"));
        b.btnEnd.setOnClickListener(v -> {
            String status = accepted || connected ? CallSignaling.ENDED
                    : (caller ? CallSignaling.MISSED : CallSignaling.DECLINED);
            endCall(status, "Call ended");
        });
        b.btnMute.setOnClickListener(v -> {
            muted = !muted;
            if (rtc != null) rtc.setMicEnabled(!muted);
            b.btnMute.setSelected(muted);
        });
        b.btnSpeaker.setOnClickListener(v -> {
            speakerOn = !speakerOn;
            audio.setSpeaker(speakerOn);
            b.btnSpeaker.setSelected(speakerOn);
        });
        b.btnCamera.setOnClickListener(v -> {
            cameraOff = !cameraOff;
            if (rtc != null) rtc.setCameraEnabled(!cameraOff);
            b.btnCamera.setSelected(cameraOff);
            b.btnFlip.setEnabled(!cameraOff);
            if (localInit) b.localView.setVisibility(cameraOff ? View.INVISIBLE : View.VISIBLE);
        });
        b.btnFlip.setOnClickListener(v -> {
            if (rtc == null) return;
            rtc.switchCamera();
            frontCamera = !frontCamera;
            if (localInit) b.localView.setMirror(frontCamera);
        });
    }

    private void showIncomingControls() {
        b.incomingControls.setVisibility(View.VISIBLE);
        b.activeControls.setVisibility(View.GONE);
    }

    private void showActiveControls() {
        b.incomingControls.setVisibility(View.GONE);
        b.activeControls.setVisibility(View.VISIBLE);
        boolean cam = video;   // startMedia() hides these again if the camera is not allowed
        b.btnCamera.setVisibility(cam ? View.VISIBLE : View.GONE);
        b.btnFlip.setVisibility(cam ? View.VISIBLE : View.GONE);
        speakerOn = video;   // video calls start on the loudspeaker
        b.btnSpeaker.setSelected(speakerOn);
    }

    private boolean granted(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void withPermissions(Runnable next) {
        List<String> need = new ArrayList<>();
        if (!granted(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO);
        if (video && !granted(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA);
        cameraAllowed = video && granted(Manifest.permission.CAMERA);
        if (need.isEmpty()) {
            next.run();
        } else {
            afterPermissions = next;
            permissionLauncher.launch(need.toArray(new String[0]));
        }
    }

    /** Opens microphone/camera, creates the WebRTC connection and starts the call service. */
    private void startMedia() {
        rtc = new WebRtcClient(this, video, this);
        if (video) {
            b.remoteView.init(rtc.eglContext(), null);
            b.remoteView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
            b.remoteView.setEnableHardwareScaler(true);
            remoteInit = true;
        }
        if (video && cameraAllowed) {
            b.localView.init(rtc.eglContext(), null);
            b.localView.setMirror(true);
            b.localView.setZOrderMediaOverlay(true);   // draw my small video above the big one
            b.localView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
            b.localView.setVisibility(View.VISIBLE);
            localInit = true;
        }
        b.btnCamera.setVisibility(video && cameraAllowed ? View.VISIBLE : View.GONE);
        b.btnFlip.setVisibility(video && cameraAllowed ? View.VISIBLE : View.GONE);
        rtc.startLocalMedia(localInit ? b.localView : null, cameraAllowed);
        rtc.createPeerConnection();
        CallService.start(this, peerName, video && cameraAllowed);
    }

    // ------------------------------------------------------------------ caller side

    private void startOutgoing() {
        startMedia();
        DatabaseReference ref = CallSignaling.newCall();
        callId = ref.getKey();

        Map<String, Object> data = new HashMap<>();
        data.put("callerId", me);
        data.put("calleeId", peerUid);
        data.put("callerName", myName);
        data.put("video", video);
        data.put("status", CallSignaling.RINGING);
        data.put("createdAt", ServerValue.TIMESTAMP);

        ref.setValue(data).addOnSuccessListener(v -> {
            if (ended) return;
            Map<String, Object> ring = new HashMap<>();
            ring.put("callId", callId);
            ring.put("callerId", me);
            ring.put("callerName", myName);
            ring.put("video", video);
            ring.put("createdAt", ServerValue.TIMESTAMP);
            // The rules only allow this if nobody else is calling them right now
            DatabaseReference inbox = CallSignaling.inbox(peerUid);
            inbox.setValue(ring).addOnSuccessListener(v2 -> {
                if (ended) return;
                // If this phone loses connection, the server cleans up for us
                inbox.onDisconnect().removeValue();
                ref.child("status").onDisconnect().setValue(CallSignaling.ENDED);
                b.tvStatus.setText("Ringing…");
                audio.startRingback();
                listenStatus();
                listenAnswer();
                listenRemoteCandidates();
                rtc.createOffer(offer -> {
                    if (!ended) ref.child("offer").setValue(CallSignaling.sdpToMap(offer));
                });
                handler.postDelayed(ringTimeout, CallConfig.RING_TIMEOUT_MS);
            }).addOnFailureListener(e -> {
                ref.child("status").setValue(CallSignaling.BUSY);
                endCall(null, peerName + " is on another call");
            });
        }).addOnFailureListener(e ->
                endCall(null, "Couldn't start the call. Check the Realtime Database rules."));
    }

    private void listenAnswer() {
        DatabaseReference ref = CallSignaling.call(callId).child("answer");
        ValueEventListener l = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                SessionDescription answer = CallSignaling.sdpFrom(snap);
                if (answer == null || answerApplied || rtc == null || ended) return;
                answerApplied = true;
                rtc.setRemoteDescription(answer, null);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) { }
        };
        ref.addValueEventListener(l);
        removeListeners.add(() -> ref.removeEventListener(l));
    }

    // ------------------------------------------------------------------ callee side

    private void accept() {
        if (accepted || ended) return;
        accepted = true;
        audio.stopRinging();
        IncomingCallNotifier.cancel(this);
        // Tell the caller right away, so their phone stops the "no answer" timer
        CallSignaling.setStatus(callId, CallSignaling.ACCEPTED);
        CallSignaling.inbox(me).removeValue();
        showActiveControls();
        b.tvStatus.setText("Connecting…");
        withPermissions(() -> {
            if (ended) return;
            startMedia();
            audio.startCall(speakerOn);
            CallSignaling.call(callId).child("status").onDisconnect().setValue(CallSignaling.ENDED);
            listenRemoteCandidates();
            listenOffer();
            handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
        });
    }

    private void listenOffer() {
        DatabaseReference ref = CallSignaling.call(callId).child("offer");
        ValueEventListener l = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                SessionDescription offer = CallSignaling.sdpFrom(snap);
                if (offer == null || offerApplied || rtc == null || ended) return;
                offerApplied = true;
                rtc.setRemoteDescription(offer, () -> rtc.createAnswer(answer -> {
                    if (!ended) {
                        CallSignaling.call(callId).child("answer").setValue(CallSignaling.sdpToMap(answer));
                    }
                }));
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) { }
        };
        ref.addValueEventListener(l);
        removeListeners.add(() -> ref.removeEventListener(l));
    }

    // ------------------------------------------------------------------ both sides

    /** Watches callData/{id}/status: accepted, declined, busy, missed, ended. */
    private void listenStatus() {
        DatabaseReference ref = CallSignaling.call(callId).child("status");
        ValueEventListener l = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                String s = snap.getValue(String.class);
                if (s == null || ended) return;
                if (!CallSignaling.RINGING.equals(s) && !CallSignaling.ACCEPTED.equals(s)) peerStatus = s;
                switch (s) {
                    case CallSignaling.ACCEPTED:
                        if (caller && !accepted) {
                            accepted = true;
                            handler.removeCallbacks(ringTimeout);
                            audio.stopRinging();
                            audio.startCall(speakerOn);
                            b.tvStatus.setText("Connecting…");
                            handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
                        }
                        break;
                    case CallSignaling.DECLINED:
                        endCall(null, "Call declined");
                        break;
                    case CallSignaling.BUSY:
                        endCall(null, peerName + " is on another call");
                        break;
                    case CallSignaling.MISSED:
                    case CallSignaling.ENDED:
                        endCall(null, connected || caller ? "Call ended" : "Missed call");
                        break;
                    default:
                        break;
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                if (!ended) endCall(null, "Call ended");
            }
        };
        ref.addValueEventListener(l);
        removeListeners.add(() -> ref.removeEventListener(l));
    }

    private void listenRemoteCandidates() {
        DatabaseReference ref = CallSignaling.call(callId)
                .child(caller ? "calleeCandidates" : "callerCandidates");
        ChildEventListener l = new ChildEventListener() {
            @Override
            public void onChildAdded(@NonNull DataSnapshot snap, String previousChildName) {
                IceCandidate c = CallSignaling.candidateFrom(snap);
                if (c != null && rtc != null && !ended) rtc.addRemoteCandidate(c);
            }

            @Override
            public void onChildChanged(@NonNull DataSnapshot snap, String previousChildName) { }

            @Override
            public void onChildRemoved(@NonNull DataSnapshot snap) { }

            @Override
            public void onChildMoved(@NonNull DataSnapshot snap, String previousChildName) { }

            @Override
            public void onCancelled(@NonNull DatabaseError error) { }
        };
        ref.addChildEventListener(l);
        removeListeners.add(() -> ref.removeEventListener(l));
    }

    // ------------------------------------------------------------------ WebRtcClient.Listener

    @Override
    public void onLocalIceCandidate(IceCandidate candidate) {
        if (callId == null || ended) return;
        CallSignaling.call(callId).child(caller ? "callerCandidates" : "calleeCandidates")
                .push().setValue(CallSignaling.candidateToMap(candidate));
    }

    @Override
    public void onRemoteVideoTrack(VideoTrack track) {
        if (!remoteInit || ended) return;
        remoteTrack = track;
        track.addSink(b.remoteView);
        b.remoteView.setVisibility(View.VISIBLE);
        b.avatar.setVisibility(View.GONE);
    }

    @Override
    public void onConnectionChanged(boolean isConnected) {
        if (ended) return;
        if (isConnected) {
            handler.removeCallbacks(connectTimeout);
            if (!connected) {
                connected = true;
                connectedAt = System.currentTimeMillis();
                audio.stopRinging();
            }
            handler.removeCallbacks(ticker);
            handler.post(ticker);
        } else {
            handler.removeCallbacks(ticker);
            b.tvStatus.setText("Reconnecting…");
        }
    }

    @Override
    public void onConnectionFailed() {
        if (!ended) endCall(CallSignaling.ENDED, "Couldn't connect. Try Wi-Fi on both phones.");
    }

    @Override
    public void onError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ ending

    /**
     * Ends the call once. statusForPeer = what to write for the other phone
     * (null when the other phone already ended it).
     */
    private void endCall(String statusForPeer, String message) {
        if (ended) return;
        ended = true;
        handler.removeCallbacks(ringTimeout);
        handler.removeCallbacks(connectTimeout);
        handler.removeCallbacks(ticker);

        if (callId != null) {
            DatabaseReference ref = CallSignaling.call(callId);
            ref.child("status").onDisconnect().cancel();
            if (statusForPeer != null) {
                // Final status + remove the big connection texts (keeps the database small)
                Map<String, Object> up = new HashMap<>();
                up.put("status", statusForPeer);
                up.put("offer", null);
                up.put("answer", null);
                up.put("callerCandidates", null);
                up.put("calleeCandidates", null);
                ref.updateChildren(up);
            }
        }
        if (caller) {
            DatabaseReference inbox = CallSignaling.inbox(peerUid);
            inbox.onDisconnect().cancel();
            inbox.removeValue();   // rules only allow this if it is still OUR call
            if (callId != null) writeCallLog(statusForPeer != null ? statusForPeer : peerStatus);
        } else if (!accepted) {
            // Still ringing: clear our inbox (after accepting it was already cleared,
            // and it may now hold somebody else's call)
            CallSignaling.inbox(me).removeValue();
        }

        release();
        b.tvStatus.setText(message);
        b.incomingControls.setVisibility(View.GONE);
        b.activeControls.setVisibility(View.GONE);
        handler.postDelayed(this::finish, 1500);
    }

    /** The caller writes one "📞 Voice call · 2:31" / "Missed voice call" line into the chat. */
    private void writeCallLog(String status) {
        String callStatus;
        long duration = 0;
        if (connectedAt > 0) {
            callStatus = "completed";
            duration = (System.currentTimeMillis() - connectedAt) / 1000;
        } else if (CallSignaling.DECLINED.equals(status)) {
            callStatus = "declined";
        } else if (CallSignaling.BUSY.equals(status)) {
            callStatus = "busy";
        } else {
            callStatus = "missed";
        }
        MessageSender.Target t = new MessageSender.Target(ChatUtil.directChatId(me, peerUid),
                false, me, myName, peerUid, peerName);
        Map<String, Object> msg = MessageSender.newMessage(t, "");
        msg.put("callType", video ? "video" : "audio");
        msg.put("callStatus", callStatus);
        msg.put("callDuration", duration);
        String icon = video ? "📹 " : "📞 ";
        String kind = video ? "video call" : "voice call";
        String preview = "completed".equals(callStatus) ? icon + (video ? "Video call" : "Voice call")
                : icon + "Missed " + kind;
        MessageSender.send(FirebaseFirestore.getInstance(), t, msg, preview);
    }

    /** Frees camera, microphone, audio mode and listeners. Safe to call twice. */
    private void release() {
        if (released) return;
        released = true;
        active = false;
        for (Runnable r : removeListeners) r.run();
        removeListeners.clear();
        if (remoteTrack != null) {
            try {
                remoteTrack.removeSink(b.remoteView);
            } catch (Exception ignored) { }
            remoteTrack = null;
        }
        if (localInit) b.localView.release();
        if (remoteInit) b.remoteView.release();
        localInit = false;
        remoteInit = false;
        if (rtc != null) rtc.dispose();
        rtc = null;
        if (audio != null) audio.release();
        CallService.stop(this);
    }

    @Override
    protected void onDestroy() {
        if (!ended && !released) {
            String status = accepted || connected ? CallSignaling.ENDED
                    : (caller ? CallSignaling.MISSED : CallSignaling.DECLINED);
            endCall(status, "Call ended");
        }
        release();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
