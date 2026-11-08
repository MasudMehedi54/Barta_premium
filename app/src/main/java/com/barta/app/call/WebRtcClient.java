package com.barta.app.call;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything WebRTC in one place: microphone, camera, and the peer-to-peer connection.
 * The audio/video goes DIRECTLY between the two phones - Firebase only carries the short
 * "offer / answer / ICE candidate" texts (see CallActivity), so calls cost nothing.
 *
 * All Listener callbacks are delivered on the main thread.
 */
public class WebRtcClient {
    private static final String TAG = "WebRtcClient";
    private static boolean factoryInitialized = false;

    public interface Listener {
        /** A network address of this phone that must be sent to the other phone. */
        void onLocalIceCandidate(IceCandidate candidate);

        void onRemoteVideoTrack(VideoTrack track);

        /** true = audio/video is flowing, false = connection lost (may come back). */
        void onConnectionChanged(boolean connected);

        /** The connection could not be made at all (usually a network that needs TURN). */
        void onConnectionFailed();

        void onError(String message);
    }

    /** Small callback used for offer/answer creation. */
    public interface SdpCallback {
        void onSdp(SessionDescription sdp);
    }

    private final Context appContext;
    private final boolean video;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final EglBase egl = EglBase.create();

    private PeerConnectionFactory factory;
    private PeerConnection pc;
    private AudioSource audioSource;
    private AudioTrack audioTrack;
    private VideoSource videoSource;
    private VideoTrack videoTrack;
    private CameraVideoCapturer capturer;
    private SurfaceTextureHelper textureHelper;
    private boolean remoteDescriptionSet = false;
    private boolean remoteVideoDelivered = false;
    private final List<IceCandidate> pendingRemoteCandidates = new ArrayList<>();
    private boolean disposed = false;

    public WebRtcClient(Context context, boolean video, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.video = video;
        this.listener = listener;

        if (!factoryInitialized) {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                    .builder(appContext).createInitializationOptions());
            factoryInitialized = true;
        }
        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext()))
                .createPeerConnectionFactory();
    }

    public EglBase.Context eglContext() {
        return egl.getEglBaseContext();
    }

    // ------------------------------------------------------------------ local media

    /** Opens the microphone, and the front camera for video calls (if cameraAllowed). */
    public void startLocalMedia(SurfaceViewRenderer localView, boolean cameraAllowed) {
        audioSource = factory.createAudioSource(new MediaConstraints());
        audioTrack = factory.createAudioTrack("barta_audio", audioSource);
        audioTrack.setEnabled(true);

        if (!video || !cameraAllowed) return;
        capturer = createCameraCapturer();
        if (capturer == null) {
            listener.onError("No camera found");
            return;
        }
        textureHelper = SurfaceTextureHelper.create("BartaCapture", egl.getEglBaseContext());
        videoSource = factory.createVideoSource(false);
        capturer.initialize(textureHelper, appContext, videoSource.getCapturerObserver());
        capturer.startCapture(640, 480, 30);
        videoTrack = factory.createVideoTrack("barta_video", videoSource);
        videoTrack.setEnabled(true);
        if (localView != null) videoTrack.addSink(localView);
    }

    private CameraVideoCapturer createCameraCapturer() {
        CameraEnumerator e = Camera2Enumerator.isSupported(appContext)
                ? new Camera2Enumerator(appContext) : new Camera1Enumerator(true);
        // Prefer the front camera, like every video-call app
        for (String name : e.getDeviceNames()) {
            if (e.isFrontFacing(name)) {
                CameraVideoCapturer c = e.createCapturer(name, null);
                if (c != null) return c;
            }
        }
        for (String name : e.getDeviceNames()) {
            CameraVideoCapturer c = e.createCapturer(name, null);
            if (c != null) return c;
        }
        return null;
    }

    public void setMicEnabled(boolean enabled) {
        if (audioTrack != null) audioTrack.setEnabled(enabled);
    }

    public void setCameraEnabled(boolean enabled) {
        if (videoTrack != null) videoTrack.setEnabled(enabled);
    }

    public boolean hasCamera() {
        return videoTrack != null;
    }

    public void switchCamera() {
        if (capturer != null) capturer.switchCamera(null);
    }

    // ------------------------------------------------------------------ peer connection

    public void createPeerConnection() {
        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(CallConfig.iceServers());
        pc = factory.createPeerConnection(config, observer);
        if (pc == null) {
            listener.onError("Could not create the call connection");
            return;
        }
        List<String> streamIds = Collections.singletonList("barta_stream");
        if (audioTrack != null) pc.addTrack(audioTrack, streamIds);
        if (videoTrack != null) pc.addTrack(videoTrack, streamIds);
    }

    private MediaConstraints sdpConstraints() {
        MediaConstraints c = new MediaConstraints();
        c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", video ? "true" : "false"));
        return c;
    }

    /** Caller: create the offer, save it as our local description, then hand it over. */
    public void createOffer(SdpCallback callback) {
        if (pc == null) return;
        pc.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                PeerConnection p = pc;
                if (p == null || disposed) return;   // call ended meanwhile
                p.setLocalDescription(new SimpleSdpObserver() {
                    @Override
                    public void onSetSuccess() {
                        main.post(() -> callback.onSdp(sdp));
                    }
                }, sdp);
            }
        }, sdpConstraints());
    }

    /** Callee: after the offer is set as remote description, create the answer. */
    public void createAnswer(SdpCallback callback) {
        if (pc == null) return;
        pc.createAnswer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                PeerConnection p = pc;
                if (p == null || disposed) return;   // call ended meanwhile
                p.setLocalDescription(new SimpleSdpObserver() {
                    @Override
                    public void onSetSuccess() {
                        main.post(() -> callback.onSdp(sdp));
                    }
                }, sdp);
            }
        }, sdpConstraints());
    }

    public void setRemoteDescription(SessionDescription sdp, Runnable onDone) {
        if (pc == null) return;
        pc.setRemoteDescription(new SimpleSdpObserver() {
            @Override
            public void onSetSuccess() {
                main.post(() -> {
                    if (disposed || pc == null) return;
                    remoteDescriptionSet = true;
                    // Candidates that arrived too early can be added now
                    for (IceCandidate c : pendingRemoteCandidates) pc.addIceCandidate(c);
                    pendingRemoteCandidates.clear();
                    if (onDone != null) onDone.run();
                });
            }
        }, sdp);
    }

    /** Call on the main thread. */
    public void addRemoteCandidate(IceCandidate candidate) {
        if (pc == null || disposed) return;
        if (remoteDescriptionSet) {
            pc.addIceCandidate(candidate);
        } else {
            pendingRemoteCandidates.add(candidate);
        }
    }

    // ------------------------------------------------------------------ callbacks from WebRTC

    private void deliverRemoteVideo(MediaStreamTrack track) {
        if (!(track instanceof VideoTrack)) return;
        VideoTrack vt = (VideoTrack) track;
        main.post(() -> {
            if (disposed || remoteVideoDelivered) return;
            remoteVideoDelivered = true;
            listener.onRemoteVideoTrack(vt);
        });
    }

    // No @Override annotations on purpose: some of these methods are "default" methods
    // in newer WebRTC versions, and this keeps the class compiling with any version.
    private final PeerConnection.Observer observer = new PeerConnection.Observer() {
        public void onSignalingChange(PeerConnection.SignalingState state) { }

        public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
            Log.d(TAG, "ICE state: " + state);
            main.post(() -> {
                if (disposed) return;
                switch (state) {
                    case CONNECTED:
                    case COMPLETED:
                        listener.onConnectionChanged(true);
                        break;
                    case DISCONNECTED:
                        listener.onConnectionChanged(false);
                        break;
                    case FAILED:
                        listener.onConnectionFailed();
                        break;
                    default:
                        break;
                }
            });
        }

        public void onIceConnectionReceivingChange(boolean receiving) { }

        public void onIceGatheringChange(PeerConnection.IceGatheringState state) { }

        public void onIceCandidate(IceCandidate candidate) {
            main.post(() -> {
                if (!disposed) listener.onLocalIceCandidate(candidate);
            });
        }

        public void onIceCandidatesRemoved(IceCandidate[] candidates) { }

        public void onAddStream(MediaStream stream) {
            if (stream != null && !stream.videoTracks.isEmpty()) deliverRemoteVideo(stream.videoTracks.get(0));
        }

        public void onRemoveStream(MediaStream stream) { }

        public void onDataChannel(DataChannel dataChannel) { }

        public void onRenegotiationNeeded() { }

        public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
            if (receiver != null) deliverRemoteVideo(receiver.track());
        }

        public void onTrack(RtpTransceiver transceiver) {
            if (transceiver != null && transceiver.getReceiver() != null) {
                deliverRemoteVideo(transceiver.getReceiver().track());
            }
        }
    };

    /** SdpObserver with empty methods, so we only write the ones we need. */
    private static class SimpleSdpObserver implements SdpObserver {
        @Override
        public void onCreateSuccess(SessionDescription sdp) { }

        @Override
        public void onSetSuccess() { }

        @Override
        public void onCreateFailure(String error) {
            Log.w(TAG, "SDP create failed: " + error);
        }

        @Override
        public void onSetFailure(String error) {
            Log.w(TAG, "SDP set failed: " + error);
        }
    }

    // ------------------------------------------------------------------ cleanup

    /** Release camera, microphone and the connection. Safe to call twice. */
    public void dispose() {
        if (disposed) return;
        disposed = true;
        try {
            if (pc != null) pc.dispose();
        } catch (Exception e) {
            Log.w(TAG, "pc dispose", e);
        }
        pc = null;
        if (capturer != null) {
            try {
                capturer.stopCapture();
            } catch (InterruptedException ignored) { }
            capturer.dispose();
            capturer = null;
        }
        if (videoSource != null) videoSource.dispose();
        if (audioSource != null) audioSource.dispose();
        if (textureHelper != null) textureHelper.dispose();
        if (factory != null) factory.dispose();
        videoSource = null;
        audioSource = null;
        textureHelper = null;
        factory = null;
        egl.release();
    }
}
