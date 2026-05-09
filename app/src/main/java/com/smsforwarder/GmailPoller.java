package com.smsforwarder;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import javax.mail.AuthenticationFailedException;
import javax.mail.Authenticator;
import javax.mail.BodyPart;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.Multipart;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.search.ComparisonTerm;
import javax.mail.search.ReceivedDateTerm;
import javax.mail.search.SearchTerm;

public class GmailPoller {

    private static final String PREFS         = "sms_fwd_prefs";
    private static final String KEY_LAST_POLL = "last_gmail_poll";
    private static final String KEY_GMAIL     = "gmail";
    private static final String KEY_PASS      = "pass";

    private static final String[] OTP_KEYWORDS = {
        "otp", "one time", "one-time", "verification code", "verify",
        "passcode", "secure code", "login code", "auth code", "your code",
        "2fa", "two-factor", "two factor", "new device", "sign in attempt",
        "security code", "access code", "expir", "valid for", "use this code",
        "bank", "transaction", "credited", "debited", "payment", "upi",
        "neft", "imps", "alert", "account access", "unusual activity"
    };

    // ─── Main poll method ─────────────────────────────────────────────────────

    public static void poll(Context context, ForwarderService service) {
        String gmail = getGmailUser(context);
        String pass  = getGmailPass(context);
        if (gmail.isEmpty() || pass.isEmpty()) return;

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long lastPoll = prefs.getLong(KEY_LAST_POLL, 0);

        // First run: last 24 hours; subsequent: last 10 minutes (with 1-min overlap)
        Date since;
        if (lastPoll == 0) {
            since = new Date(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24));
        } else {
            since = new Date(lastPoll - TimeUnit.MINUTES.toMillis(1));
        }

        prefs.edit().putLong(KEY_LAST_POLL, System.currentTimeMillis()).apply();

        try {
            Session session = buildImapSession(gmail, pass);
            Store store = session.getStore("imaps");
            store.connect("imap.gmail.com", 993, gmail, pass);

            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);

            SearchTerm dateTerm = new ReceivedDateTerm(ComparisonTerm.GE, since);
            Message[] messages  = inbox.search(dateTerm);

            for (Message msg : messages) {
                String subject = msg.getSubject() != null ? msg.getSubject() : "";
                String body    = extractText(msg);
                if (isOtpRelated(subject, body)) {
                    String from = (msg.getFrom() != null && msg.getFrom().length > 0)
                        ? msg.getFrom()[0].toString() : "Gmail";
                    service.forwardGmailMessage(from, subject, body);
                }
            }

            inbox.close(false);
            store.close();

        } catch (AuthenticationFailedException e) {
            // Wrong password — don't crash, service continues for SMS
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ─── Validate credentials (blocking — run on background thread) ──────────

    public static String testLogin(String gmail, String pass) {
        try {
            Session session = buildImapSession(gmail, pass);
            Store store = session.getStore("imaps");
            store.connect("imap.gmail.com", 993, gmail, pass);
            store.close();
            return null; // success
        } catch (AuthenticationFailedException e) {
            return "Galat password hai. Gmail ka sahi password enter karein.";
        } catch (Exception e) {
            return "Connection error. Internet check karein aur dobara try karein.";
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static Session buildImapSession(String gmail, String pass) {
        Properties props = new Properties();
        props.put("mail.imaps.host", "imap.gmail.com");
        props.put("mail.imaps.port", "993");
        props.put("mail.imaps.ssl.enable", "true");
        props.put("mail.imaps.ssl.trust", "imap.gmail.com");
        props.put("mail.imaps.connectiontimeout", "20000");
        props.put("mail.imaps.timeout", "20000");
        final String u = gmail, p = pass;
        return Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(u, p);
            }
        });
    }

    private static boolean isOtpRelated(String subject, String body) {
        String s = subject.toLowerCase();
        String b = body.toLowerCase();
        for (String kw : OTP_KEYWORDS) {
            if (s.contains(kw) || b.contains(kw)) return true;
        }
        return false;
    }

    private static String extractText(Message msg) {
        try {
            Object content = msg.getContent();
            if (content instanceof String) return (String) content;
            if (content instanceof Multipart) return parseMultipart((Multipart) content);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    private static String parseMultipart(Multipart mp) {
        StringBuilder sb = new StringBuilder();
        try {
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart bp = mp.getBodyPart(i);
                if (bp.isMimeType("text/plain")) {
                    sb.append(bp.getContent().toString()).append("\n");
                } else if (bp.isMimeType("text/html")) {
                    String html = bp.getContent().toString();
                    sb.append(html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim());
                } else if (bp.getContent() instanceof Multipart) {
                    sb.append(parseMultipart((Multipart) bp.getContent()));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return sb.toString();
    }

    private static String getGmailUser(Context context) {
        if (!AppConfig.GMAIL_USER.isEmpty()) return AppConfig.GMAIL_USER;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                      .getString(KEY_GMAIL, "");
    }

    private static String getGmailPass(Context context) {
        if (!AppConfig.GMAIL_APP_PASSWORD.isEmpty()) return AppConfig.GMAIL_APP_PASSWORD;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                      .getString(KEY_PASS, "");
    }
}
