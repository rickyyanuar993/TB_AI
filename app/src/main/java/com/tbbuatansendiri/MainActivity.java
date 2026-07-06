package com.tbbuatansendiri;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "TBBuatanSendiriPrefs";
    private static final String KEY_ACTIVE_PROFILE = "active_profile";
    private static final String KEY_TARGET_APPS = "target_apps"; // JSON array of package names
    private static final String BACKUP_ROOT = "/sdcard/TB_Buatan_Sendiri/profiles";

    private static final int REQUEST_STORAGE_PERMISSION = 300;

    private TextView tvActiveProfile;
    private CheckBox chkTargetWa, chkTargetWaBiz;
    private Button btnSelectOtherApps, btnAddProfile, btnManualBackup, btnRefresh;
    private TextView tvSelectedOtherApps, tvConsoleLog, tvProgressText, btnClearConsole;
    private LinearLayout layoutProfileSlots, layoutProgress;
    private ScrollView scrollConsole;

    private SharedPreferences prefs;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private Set<String> targetPackages = new LinkedHashSet<>();
    private List<String> profileList = new ArrayList<>();
    private String activeProfile = "01";

    // Store custom apps selected by user
    private List<AppRecord> allInstalledApps = new ArrayList<>();
    private Set<String> customSelectedPackages = new LinkedHashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Bind views
        tvActiveProfile = findViewById(R.id.tvActiveProfile);
        chkTargetWa = findViewById(R.id.chkTargetWa);
        chkTargetWaBiz = findViewById(R.id.chkTargetWaBiz);
        btnSelectOtherApps = findViewById(R.id.btnSelectOtherApps);
        btnAddProfile = findViewById(R.id.btnAddProfile);
        btnManualBackup = findViewById(R.id.btnManualBackup);
        btnRefresh = findViewById(R.id.btnRefresh);
        tvSelectedOtherApps = findViewById(R.id.tvSelectedOtherApps);
        tvConsoleLog = findViewById(R.id.tvConsoleLog);
        btnClearConsole = findViewById(R.id.btnClearConsole);
        layoutProfileSlots = findViewById(R.id.layoutProfileSlots);
        layoutProgress = findViewById(R.id.layoutProgress);
        tvProgressText = findViewById(R.id.tvProgressText);
        scrollConsole = findViewById(R.id.scrollConsole);

        // Load settings — sanitize to prevent shell injection if prefs are tampered
        activeProfile = sanitizeProfileName(prefs.getString(KEY_ACTIVE_PROFILE, "01"));
        if (activeProfile.isEmpty()) activeProfile = "01";
        loadCustomSelectedPackages();

        // Listeners
        chkTargetWa.setOnCheckedChangeListener((b, checked) -> updateTargetPackagesList());
        chkTargetWaBiz.setOnCheckedChangeListener((b, checked) -> updateTargetPackagesList());
        btnSelectOtherApps.setOnClickListener(v -> showAppSelectionDialog());
        btnAddProfile.setOnClickListener(v -> showAddProfileDialog());
        btnManualBackup.setOnClickListener(v -> runManualBackup());
        btnRefresh.setOnClickListener(v -> refreshState());
        btnClearConsole.setOnClickListener(v -> {
            tvConsoleLog.setText("");
            logConsole("[System] Console cleared.");
        });

        // Initialize state
        checkRootAndPermissions();
        updateTargetPackagesList();
        loadProfilesFromDisk();
        updateUI();
    }

    private void logConsole(final String text) {
        mainHandler.post(() -> {
            tvConsoleLog.append(text + "\n");
            scrollConsole.post(() -> scrollConsole.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void showProgress(final boolean show, final String text) {
        mainHandler.post(() -> {
            layoutProgress.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                tvProgressText.setText(text);
                btnManualBackup.setEnabled(false);
                btnRefresh.setEnabled(false);
                btnAddProfile.setEnabled(false);
            } else {
                btnManualBackup.setEnabled(true);
                btnRefresh.setEnabled(true);
                btnAddProfile.setEnabled(true);
            }
        });
    }

    // ─── CHECKS & PERMISSIONS ───────────────────────────────────────────────────

    private void checkRootAndPermissions() {
        logConsole("[System] Checking root status...");
        executor.execute(() -> {
            ShellResult r = runRootCommand("id");
            if (r.exitCode == 0 && r.stdout.contains("uid=0")) {
                logConsole("[System] Root status: ACTIVE (uid=0)");
                // Create backup root directory via root shell
                runRootCommand("mkdir -p " + BACKUP_ROOT);
            } else {
                logConsole("[System] Root status: FAILED/DENIED.");
                logConsole("[Warning] Application needs root permissions to access app data directories!");
                mainHandler.post(() -> {
                    Toast.makeText(MainActivity.this, "Akses root (Magisk) dibutuhkan!", Toast.LENGTH_LONG).show();
                });
            }
        });

        // Request storage permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                logConsole("[System] Requesting All Files Access storage permission...");
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.addCategory("android.intent.category.DEFAULT");
                    intent.setData(Uri.parse(String.format("package:%s", getPackageName())));
                    startActivity(intent);
                } catch (Exception e) {
                    Intent intent = new Intent();
                    intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                }
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_STORAGE_PERMISSION);
            }
        }
    }

    // ─── TARGET APPS ─────────────────────────────────────────────────────────────

    private void updateTargetPackagesList() {
        targetPackages.clear();
        if (chkTargetWa.isChecked()) {
            targetPackages.add("com.whatsapp");
        }
        if (chkTargetWaBiz.isChecked()) {
            targetPackages.add("com.whatsapp.w4b");
        }
        targetPackages.addAll(customSelectedPackages);

        // Update target apps label
        if (customSelectedPackages.isEmpty()) {
            tvSelectedOtherApps.setText("Tidak ada aplikasi tambahan dipilih");
        } else {
            tvSelectedOtherApps.setText("Aplikasi lain: " + String.join(", ", customSelectedPackages));
        }
    }

    private void loadCustomSelectedPackages() {
        String saved = prefs.getString(KEY_TARGET_APPS, "");
        customSelectedPackages.clear();
        if (!saved.isEmpty()) {
            String[] split = saved.split(",");
            for (String p : split) {
                String trimmed = p.trim();
                if (!trimmed.isEmpty() && isValidPackageName(trimmed)) {
                    customSelectedPackages.add(trimmed);
                }
            }
        }
    }

    private void saveCustomSelectedPackages() {
        StringBuilder sb = new StringBuilder();
        for (String p : customSelectedPackages) {
            if (sb.length() > 0) sb.append(",");
            sb.append(p);
        }
        prefs.edit().putString(KEY_TARGET_APPS, sb.toString()).apply();
    }

    private void showAppSelectionDialog() {
        showProgress(true, "Membaca daftar aplikasi terinstal...");
        executor.execute(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            allInstalledApps.clear();
            for (ApplicationInfo app : apps) {
                // Filter user apps only
                if (((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0) || ((app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)) {
                    // Exclude this app itself
                    if (app.packageName.equals(getPackageName())) continue;
                    // Exclude default WA and WA Biz checkboxes to avoid redundancy
                    if (app.packageName.equals("com.whatsapp") || app.packageName.equals("com.whatsapp.w4b")) continue;

                    AppRecord r = new AppRecord();
                    r.packageName = app.packageName;
                    r.label = app.loadLabel(pm).toString();
                    allInstalledApps.add(r);
                }
            }
            // Sort alphabetically
            Collections.sort(allInstalledApps, (a, b) -> a.label.compareToIgnoreCase(b.label));

            mainHandler.post(() -> {
                showProgress(false, "");
                if (allInstalledApps.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Tidak ada aplikasi pihak ketiga ditemukan.", Toast.LENGTH_SHORT).show();
                    return;
                }

                // Prepare list views for dialog
                final String[] items = new String[allInstalledApps.size()];
                final boolean[] checkedItems = new boolean[allInstalledApps.size()];
                for (int i = 0; i < allInstalledApps.size(); i++) {
                    AppRecord app = allInstalledApps.get(i);
                    items[i] = app.label + "\n(" + app.packageName + ")";
                    checkedItems[i] = customSelectedPackages.contains(app.packageName);
                }

                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Pilih Aplikasi Tambahan")
                        .setMultiChoiceItems(items, checkedItems, (dialog, which, isChecked) -> {
                            AppRecord app = allInstalledApps.get(which);
                            if (isChecked) {
                                customSelectedPackages.add(app.packageName);
                            } else {
                                customSelectedPackages.remove(app.packageName);
                            }
                        })
                        .setPositiveButton("Simpan", (dialog, which) -> {
                            saveCustomSelectedPackages();
                            updateTargetPackagesList();
                            logConsole("[System] Target apps updated: " + targetPackages);
                        })
                        .setNegativeButton("Batal", null)
                        .show();
            });
        });
    }

    // ─── PROFILE LOGIC ───────────────────────────────────────────────────────────

    private void loadProfilesFromDisk() {
        profileList.clear();
        // Add default profile "01" if empty
        profileList.add("01");

        // Try listing folder profiles via root command (bulletproof)
        executor.execute(() -> {
            ShellResult r = runRootCommand("ls " + BACKUP_ROOT);
            if (r.exitCode == 0 && !r.stdout.trim().isEmpty()) {
                String[] dirs = r.stdout.split("\n");
                for (String dir : dirs) {
                    String d = sanitizeProfileName(dir.trim());
                    if (!d.isEmpty() && !d.equals("01") && !profileList.contains(d)) {
                        profileList.add(d);
                    }
                }
                // Sort profiles
                Collections.sort(profileList);
            }
            mainHandler.post(this::renderProfileSlots);
        });
    }

    private void showAddProfileDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Tambah Profil Baru");

        final EditText input = new EditText(this);
        input.setHint("Masukkan nama profil (misal: 02, WhatsApp2, dll.)");
        input.setPadding(40, 20, 40, 20);
        builder.setView(input);

        builder.setPositiveButton("Tambah", (dialog, which) -> {
            String name = input.getText().toString().trim().replaceAll("[^a-zA-Z0-9_-]", "");
            if (name.isEmpty()) {
                Toast.makeText(MainActivity.this, "Nama profil tidak valid!", Toast.LENGTH_SHORT).show();
                return;
            }
            if (profileList.contains(name)) {
                Toast.makeText(MainActivity.this, "Profil sudah ada!", Toast.LENGTH_SHORT).show();
                return;
            }

            profileList.add(name);
            Collections.sort(profileList);
            renderProfileSlots();
            logConsole("[System] Profil slot '" + name + "' ditambahkan.");

            // Create profile folder
            executor.execute(() -> runRootCommand("mkdir -p " + BACKUP_ROOT + "/" + name));
        });
        builder.setNegativeButton("Batal", null);
        builder.show();
    }

    private void renderProfileSlots() {
        layoutProfileSlots.removeAllViews();

        float dp = getResources().getDisplayMetrics().density;
        int padding8 = (int) (8 * dp);
        int padding12 = (int) (12 * dp);

        for (final String profileName : profileList) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, padding8, 0, padding8);

            // Left indicator bar
            View indicator = new View(this);
            boolean isActive = profileName.equals(activeProfile);
            indicator.setBackgroundColor(isActive ? 0xFFF59E0B : 0xFF475569);
            LinearLayout.LayoutParams indicatorLp = new LinearLayout.LayoutParams((int) (4 * dp), LinearLayout.LayoutParams.MATCH_PARENT);
            indicator.setLayoutParams(indicatorLp);
            row.addView(indicator);

            // Profile info container
            LinearLayout infoLayout = new LinearLayout(this);
            infoLayout.setOrientation(LinearLayout.VERTICAL);
            infoLayout.setPadding(padding12, 0, padding12, 0);
            LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            infoLayout.setLayoutParams(infoLp);

            TextView tvName = new TextView(this);
            tvName.setText("Profil " + profileName);
            tvName.setTextColor(isActive ? 0xFFF59E0B : 0xFFE2E8F0);
            tvName.setTextSize(16);
            tvName.setTypeface(null, android.graphics.Typeface.BOLD);
            infoLayout.addView(tvName);

            // Check if profile folder has backup files
            final TextView tvStatus = new TextView(this);
            tvStatus.setText("Checking backup state...");
            tvStatus.setTextColor(0xFF64748B);
            tvStatus.setTextSize(11);
            infoLayout.addView(tvStatus);
            row.addView(infoLayout);

            // Action Buttons layout
            LinearLayout btnLayout = new LinearLayout(this);
            btnLayout.setOrientation(LinearLayout.HORIZONTAL);
            btnLayout.setGravity(Gravity.END);

            // Apply Button
            Button btnApply = new Button(this);
            btnApply.setText(isActive ? "AKTIF" : "TERAPKAN");
            btnApply.setTextSize(10);
            btnApply.setEnabled(!isActive);
            btnApply.setTextColor(0xFF0F172A);
            btnApply.setBackgroundTintList(android.content.res.ColorStateList.valueOf(isActive ? 0xFF64748B : 0xFF38BDF8));
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams((int) (90 * dp), (int) (38 * dp));
            btnApply.setLayoutParams(btnLp);
            btnApply.setOnClickListener(v -> switchProfile(profileName));
            btnLayout.addView(btnApply);

            // Delete Button (only if not active)
            if (!isActive && !profileName.equals("01")) {
                ImageButton btnDelete = new ImageButton(this);
                btnDelete.setImageResource(android.R.drawable.ic_menu_delete);
                btnDelete.setColorFilter(0xFFEF4444);
                btnDelete.setBackgroundColor(0);
                LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams((int) (40 * dp), (int) (38 * dp));
                btnDelete.setLayoutParams(delLp);
                btnDelete.setOnClickListener(v -> confirmDeleteProfile(profileName));
                btnLayout.addView(btnDelete);
            }

            row.addView(btnLayout);
            layoutProfileSlots.addView(row);

            // Update backup file presence label asynchronously
            executor.execute(() -> {
                ShellResult r = runRootCommand("find " + BACKUP_ROOT + "/" + profileName + " -name '*.tar.gz'");
                final String statusText;
                if (r.exitCode == 0 && !r.stdout.trim().isEmpty()) {
                    int count = r.stdout.split("\n").length;
                    statusText = "💾 " + count + " aplikasi terbackup";
                } else {
                    statusText = "⚪ Kosong (Fresh)";
                }
                mainHandler.post(() -> tvStatus.setText(statusText));
            });
        }
    }

    private void confirmDeleteProfile(final String profileName) {
        new AlertDialog.Builder(this)
                .setTitle("Hapus Profil")
                .setMessage("Apakah Anda yakin ingin menghapus profil '" + profileName + "' beserta file backup-nya?")
                .setPositiveButton("Hapus", (d, w) -> {
                    showProgress(true, "Menghapus profil...");
                    executor.execute(() -> {
                        runRootCommand("rm -rf " + BACKUP_ROOT + "/" + profileName);
                        profileList.remove(profileName);
                        logConsole("[System] Profil '" + profileName + "' dihapus.");
                        mainHandler.post(() -> {
                            showProgress(false, "");
                            renderProfileSlots();
                        });
                    });
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    // ─── BACKUP / RESTORE EXECUTION ──────────────────────────────────────────────

    private void runManualBackup() {
        if (targetPackages.isEmpty()) {
            Toast.makeText(this, "Pilih minimal 1 aplikasi target!", Toast.LENGTH_SHORT).show();
            return;
        }
        showProgress(true, "Membackup profil '" + activeProfile + "'...");
        logConsole("\n[Backup] Memulai backup manual untuk Profil: " + activeProfile);
        executor.execute(() -> {
            boolean success = backupCurrentProfile(activeProfile);
            showProgress(false, "");
            mainHandler.post(() -> {
                if (success) {
                    Toast.makeText(MainActivity.this, "Backup manual selesai!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this, "Backup manual gagal! Cek log console.", Toast.LENGTH_LONG).show();
                }
                renderProfileSlots();
            });
        });
    }

    private void switchProfile(final String targetProfile) {
        if (targetPackages.isEmpty()) {
            Toast.makeText(this, "Pilih minimal 1 aplikasi target sebelum berpindah!", Toast.LENGTH_SHORT).show();
            return;
        }

        // Confirmation dialog to prevent accidental switch
        new AlertDialog.Builder(this)
                .setTitle("⚠️ Konfirmasi Pindah Profil")
                .setMessage("Data aplikasi target akan di-backup ke profil saat ini ('" + activeProfile + "'), "
                        + "lalu data aplikasi akan dihapus dan diganti dengan data profil '" + targetProfile + "'.\n\n"
                        + "Aplikasi target: " + targetPackages + "\n\nLanjutkan?")
                .setPositiveButton("Ya, Pindah", (dlg, w) -> executeSwitchProfile(targetProfile))
                .setNegativeButton("Batal", null)
                .show();
    }

    private void executeSwitchProfile(final String targetProfile) {

        final String currentProfile = activeProfile;
        showProgress(true, "Menyimpan profil '" + currentProfile + "' & berpindah...");
        logConsole("\n[Profile Switch] Berpindah profil dari '" + currentProfile + "' ke '" + targetProfile + "'");

        executor.execute(() -> {
            // Step 1: Backup current profile first
            logConsole("[Step 1] Membackup data profil aktif saat ini (" + currentProfile + ")...");
            boolean backupSuccess = backupCurrentProfile(currentProfile);
            if (!backupSuccess) {
                logConsole("[Error] Gagal membackup profil saat ini. Membatalkan perpindahan profil!");
                showProgress(false, "");
                return;
            }

            // Step 2: Clear app data and restore/setup target profile
            logConsole("[Step 2] Memproses restore/pembersihan untuk profil tujuan (" + targetProfile + ")...");
            boolean restoreSuccess = true;
            for (String pkg : targetPackages) {
                if (pkg.equals(getPackageName())) {
                    logConsole("[Warn] Melewati backup/restore aplikasi ini sendiri (" + pkg + ") untuk mencegah kerusakan state.");
                    continue;
                }
                if (!isValidPackageName(pkg)) {
                    logConsole("[Error] Package name tidak valid, dilewati: " + pkg);
                    continue;
                }
                if (!appInstalled(pkg)) {
                    logConsole("[Warn] Aplikasi " + pkg + " tidak terinstal di perangkat. Dilewati.");
                    continue;
                }

                // Force stop app
                logConsole("[Command] Menghentikan " + pkg);
                runRootCommand("am force-stop " + pkg);

                // Clear current app data (creates clean state)
                logConsole("[Command] pm clear " + pkg);
                ShellResult clearRes = runRootCommand("pm clear " + pkg);
                if (clearRes.exitCode != 0) {
                    logConsole("[Error] Gagal membersihkan data " + pkg + ": " + clearRes.stderr);
                }

                // Check if target profile has backup
                String backupPath = BACKUP_ROOT + "/" + targetProfile + "/" + pkg + "/data.tar.gz";
                ShellResult fileCheck = runRootCommand("[ -f " + backupPath + " ] && echo 'exists'");
                if (fileCheck.stdout.trim().equals("exists")) {
                    // Restore backup files
                    logConsole("[Restore] Mengekstrak file backup untuk " + pkg + "...");
                    
                    // Delete fresh directory files to extract clean zip
                    runRootCommand("rm -rf /data/data/" + pkg + "/*");
                    
                    // Extract
                    String extractCmd = "tar -xzf " + backupPath + " -C /data/data/" + pkg + "/";
                    logConsole("[Command] su -c '" + extractCmd + "'");
                    ShellResult extRes = runRootCommand(extractCmd);
                    if (extRes.exitCode != 0) {
                        logConsole("[Error] Gagal mengekstrak: " + extRes.stderr);
                        restoreSuccess = false;
                        continue;
                    }

                    // Fix UID ownership
                    int uid = getAppUid(pkg);
                    if (uid > 0) {
                        logConsole("[Restore] Mengatur kepemilikan data ke UID " + uid);
                        runRootCommand("chown -R " + uid + ":" + uid + " /data/data/" + pkg);
                        runRootCommand("chmod 700 /data/data/" + pkg);
                        runRootCommand("chmod -R 600 /data/data/" + pkg + "/*");
                        runRootCommand("find /data/data/" + pkg + " -type d -exec chmod 700 {} \\;");
                    } else {
                        logConsole("[Error] Gagal mendapatkan UID untuk " + pkg + ". Hak akses data mungkin bermasalah!");
                    }

                    // Fix SELinux Contexts
                    logConsole("[Restore] restorecon -R /data/data/" + pkg);
                    runRootCommand("restorecon -R /data/data/" + pkg);
                } else {
                    // It's a fresh profile, already cleared by pm clear
                    logConsole("[Restore] Profil baru/kosong untuk " + pkg + " (Fresh data applied)");
                }
            }

            // Step 3: Complete switch
            if (restoreSuccess) {
                activeProfile = targetProfile;
                prefs.edit().putString(KEY_ACTIVE_PROFILE, activeProfile).apply();
                logConsole("[Success] Berhasil berpindah ke profil '" + targetProfile + "'!");
            } else {
                logConsole("[Warn] Proses restore selesai dengan beberapa kesalahan. Profil tetap dialihkan.");
                activeProfile = targetProfile;
                prefs.edit().putString(KEY_ACTIVE_PROFILE, activeProfile).apply();
            }

            showProgress(false, "");
            mainHandler.post(() -> {
                updateUI();
                renderProfileSlots();
                Toast.makeText(MainActivity.this, "Berpindah ke profil " + targetProfile, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private boolean backupCurrentProfile(String profileName) {
        boolean overallSuccess = true;
        for (String pkg : targetPackages) {
            if (pkg.equals(getPackageName())) {
                logConsole("[Warn] Melewati backup aplikasi ini sendiri (" + pkg + ").");
                continue;
            }
            if (!isValidPackageName(pkg)) {
                logConsole("[Error] Package name tidak valid, dilewati: " + pkg);
                continue;
            }
            if (!appInstalled(pkg)) {
                logConsole("[Warn] Aplikasi " + pkg + " tidak terinstal di perangkat. Dilewati.");
                continue;
            }

            logConsole("[Backup] Memproses backup data aplikasi: " + pkg);
            
            // Create target folders
            String targetDir = BACKUP_ROOT + "/" + profileName + "/" + pkg;
            runRootCommand("mkdir -p " + targetDir);

            // Compress data
            // We use tar -czf to backup /data/data/<pkg> to target folder. We do -C to go inside data dir and compress relative files
            String backupCmd = "tar -czf " + targetDir + "/data.tar.gz -C /data/data/" + pkg + " .";
            logConsole("[Command] su -c '" + backupCmd + "'");
            ShellResult r = runRootCommand(backupCmd);
            if (r.exitCode == 0) {
                logConsole("[Backup] Berhasil membackup data " + pkg + " ke " + targetDir + "/data.tar.gz");
            } else {
                logConsole("[Error] Gagal membackup data " + pkg + ": " + r.stderr);
                overallSuccess = false;
            }
        }
        return overallSuccess;
    }

    private void refreshState() {
        showProgress(true, "Memuat ulang...");
        loadProfilesFromDisk();
        executor.execute(() -> {
            try { Thread.sleep(500); } catch(Exception ignored){}
            showProgress(false, "");
            logConsole("[System] Status disegarkan.");
        });
    }

    private void updateUI() {
        tvActiveProfile.setText(activeProfile + " (Aktif)");
    }

    // ─── UTILITIES ───────────────────────────────────────────────────────────────

    /**
     * Sanitize profile name: strip all characters except alphanumeric, underscore, hyphen.
     * This prevents shell injection and path traversal attacks when profile names
     * are used in root commands like rm -rf, tar, chown, etc.
     */
    private String sanitizeProfileName(String name) {
        if (name == null) return "";
        return name.replaceAll("[^a-zA-Z0-9_-]", "");
    }

    /**
     * Validate an Android package name against a strict whitelist regex.
     * Valid package names contain only letters, digits, underscores, and dots.
     * They must have at least one dot (e.g. com.example) and no consecutive dots.
     * This prevents shell injection when package names are used in root commands
     * targeting /data/data/<package_name>.
     */
    private boolean isValidPackageName(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        // Strict Android package name: letters, digits, underscores, dots only.
        // Must contain at least one dot. No consecutive dots. No leading/trailing dots.
        if (!pkg.matches("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")) return false;
        // Extra safety: reject anything with shell metacharacters or path traversal
        if (pkg.contains("..") || pkg.contains("/") || pkg.contains(" ") ||
            pkg.contains(";") || pkg.contains("|") || pkg.contains("&") ||
            pkg.contains("`") || pkg.contains("$") || pkg.contains("(") ||
            pkg.contains(")") || pkg.contains("'") || pkg.contains("\"")) {
            return false;
        }
        return true;
    }

    private boolean appInstalled(String packageName) {
        if (!isValidPackageName(packageName)) return false;
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            // Fallback via root shell: check if the app's private data folder exists
            ShellResult r = runRootCommand("[ -d /data/data/" + packageName + " ] && echo 'exists'");
            return r.stdout.trim().equals("exists");
        }
    }

    private int getAppUid(String packageName) {
        try {
            return getPackageManager().getPackageInfo(packageName, 0).applicationInfo.uid;
        } catch (Exception e) {
            return -1;
        }
    }

    private ShellResult runRootCommand(String command) {
        ShellResult res = new ShellResult();
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        try {
            // Executes command directly in su shell
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            
            BufferedReader osOut = new BufferedReader(new InputStreamReader(p.getInputStream()));
            BufferedReader osErr = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            
            String line;
            while ((line = osOut.readLine()) != null) {
                out.append(line).append("\n");
            }
            while ((line = osErr.readLine()) != null) {
                err.append(line).append("\n");
            }
            res.exitCode = p.waitFor();
        } catch (Exception e) {
            res.exitCode = -1;
            err.append(e.getMessage());
        }
        res.stdout = out.toString().trim();
        res.stderr = err.toString().trim();
        return res;
    }

    // Helper models
    private static class ShellResult {
        int exitCode;
        String stdout = "";
        String stderr = "";
    }

    private static class AppRecord {
        String packageName;
        String label;
    }
}
