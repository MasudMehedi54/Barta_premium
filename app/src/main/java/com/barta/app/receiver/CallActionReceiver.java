package com.barta.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.barta.app.call.CallSignaling;
import com.barta.app.call.IncomingCallNotifier;
import com.google.firebase.auth.FirebaseAuth;

/** "Decline" button on the incoming-call notification. */
public class CallActionReceiver extends BroadcastReceiver {
    private static final String ACTION_DECLINE = "com.barta.app.DECLINE_CALL";
    private static final String EX_CALL_ID = "callId";

    public static Intent decline(Context ctx, String callId, String callerId) {
        return new Intent(ctx, CallActionReceiver.class)
                .setAction(ACTION_DECLINE)
                .putExtra(EX_CALL_ID, callId)
                .putExtra("callerId", callerId);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_DECLINE.equals(intent.getAction())) return;
        String callId = intent.getStringExtra(EX_CALL_ID);
        String me = FirebaseAuth.getInstance().getUid();
        IncomingCallNotifier.cancel(context);
        if (callId == null || me == null) return;
        CallSignaling.setStatus(callId, CallSignaling.DECLINED);
        CallSignaling.inbox(me).removeValue();
    }
}
