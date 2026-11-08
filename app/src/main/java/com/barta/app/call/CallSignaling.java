package com.barta.app.call;

import com.barta.app.data.Rtdb;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;

import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

import java.util.HashMap;
import java.util.Map;

/**
 * The "call mailbox" in the Realtime Database (free). Only short texts go here:
 *
 *   calls/{calleeUid}                 { callId, callerId, callerName, video, createdAt }
 *                                     = "someone is calling you" (one at a time -> busy)
 *   callData/{callId}                 { callerId, calleeId, callerName, video, status, createdAt }
 *   callData/{callId}/offer           { type, sdp }   the caller's connection description
 *   callData/{callId}/answer          { type, sdp }   the callee's reply
 *   callData/{callId}/callerCandidates/{auto}   network addresses of the caller
 *   callData/{callId}/calleeCandidates/{auto}   network addresses of the callee
 *
 * status: ringing -> accepted -> ended, or declined / busy / missed.
 */
public final class CallSignaling {
    public static final String RINGING = "ringing";
    public static final String ACCEPTED = "accepted";
    public static final String DECLINED = "declined";
    public static final String BUSY = "busy";
    public static final String MISSED = "missed";
    public static final String ENDED = "ended";

    private CallSignaling() { }

    public static DatabaseReference inbox(String uid) {
        return Rtdb.root().child("calls").child(uid);
    }

    /** A new callData entry with an automatic, unique id. */
    public static DatabaseReference newCall() {
        return Rtdb.root().child("callData").push();
    }

    public static DatabaseReference call(String callId) {
        return Rtdb.root().child("callData").child(callId);
    }

    public static Map<String, Object> sdpToMap(SessionDescription sdp) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", sdp.type.canonicalForm());
        m.put("sdp", sdp.description);
        return m;
    }

    public static SessionDescription sdpFrom(DataSnapshot snap) {
        String type = snap.child("type").getValue(String.class);
        String sdp = snap.child("sdp").getValue(String.class);
        if (type == null || sdp == null) return null;
        return new SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp);
    }

    public static Map<String, Object> candidateToMap(IceCandidate c) {
        Map<String, Object> m = new HashMap<>();
        m.put("sdpMid", c.sdpMid);
        m.put("sdpMLineIndex", c.sdpMLineIndex);
        m.put("candidate", c.sdp);
        return m;
    }

    public static IceCandidate candidateFrom(DataSnapshot snap) {
        String mid = snap.child("sdpMid").getValue(String.class);
        Long index = snap.child("sdpMLineIndex").getValue(Long.class);
        String sdp = snap.child("candidate").getValue(String.class);
        if (sdp == null || index == null) return null;
        return new IceCandidate(mid, index.intValue(), sdp);
    }

    /** Ends a call from outside the call screen (e.g. "Decline" in the notification). */
    public static void setStatus(String callId, String status) {
        call(callId).child("status").setValue(status);
    }
}
