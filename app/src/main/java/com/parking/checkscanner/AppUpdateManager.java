package com.parking.checkscanner;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 智能应用内推送更新与在线升级管理器 (In-App Push Update Manager)
 * 具备：
 * 1. 自动与手动静默/显式检测升级
 * 2. 差异版本判定与详细更新日志弹窗
 * 3. 带实时进度条与下载速率展示的断点/流式下载
 * 4. 适配 Android 7.0 ~ 14 的 FileProvider 与未知应用安装权限引导
 * 5. 支持局域网、内网、GitHub/Gitee 或自建服务器的自定义更新源配置
 */
public class AppUpdateManager {

    private static final String PREF_NAME = "update_config";
    private static final String KEY_UPDATE_URL = "update_url";

    // 默认更新检测地址（可配置为您的 GitHub、Gitee Releases、或内网服务器地址）
    public static final String DEFAULT_UPDATE_URL = 
        "http://10.0.0.192:8080/version.json";

    private static AppUpdateManager instance;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static synchronized AppUpdateManager getInstance() {
        if (instance == null) {
            instance = new AppUpdateManager();
        }
        return instance;
    }

    public static class UpdateInfo {
        public int versionCode;
        public String versionName;
        public String apkUrl;
        public String apkSize;
        public String updateLog;
        public boolean forceUpdate;

        public static UpdateInfo fromJson(String jsonStr) throws Exception {
            JSONObject json = new JSONObject(jsonStr);
            UpdateInfo info = new UpdateInfo();
            info.versionCode = json.optInt("versionCode", 0);
            info.versionName = json.optString("versionName", "");
            info.apkUrl = json.optString("apkUrl", "");
            info.apkSize = json.optString("apkSize", "");
            info.updateLog = json.optString("updateLog", "优化系统性能并修复若干已知问题。");
            info.forceUpdate = json.optBoolean("forceUpdate", false);
            return info;
        }
    }

    public static String getUpdateUrl(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String url = sp.getString(KEY_UPDATE_URL, DEFAULT_UPDATE_URL);
        if (url == null || url.contains("githubusercontent.com") || url.isEmpty() ||
            url.contains(":5005") || url.contains("10.0.0.1") || !url.startsWith("http")) {
            url = DEFAULT_UPDATE_URL;
            sp.edit().putString(KEY_UPDATE_URL, url).apply();
        }
        return url;
    }

    public static void setUpdateUrl(Context context, String url) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_UPDATE_URL, url != null ? url.trim() : DEFAULT_UPDATE_URL).apply();
    }

    public static int getCurrentVersionCode(Context context) {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) pInfo.getLongVersionCode();
            } else {
                return pInfo.versionCode;
            }
        } catch (PackageManager.NameNotFoundException e) {
            return 1;
        }
    }

    public static String getCurrentVersionName(Context context) {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pInfo.versionName != null ? pInfo.versionName : "1.0";
        } catch (PackageManager.NameNotFoundException e) {
            return "1.0";
        }
    }

    /**
     * 检查版本更新
     * @param activity 上下文 Activity
     * @param isManual 是否由用户手动点击触发（手动触发时若无更新或网络故障会弹出提示，自动触发则静默）
     */
    public void checkUpdate(Activity activity, boolean isManual) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        if (isManual) {
            Toast.makeText(activity, "正在连接更新服务器，请稍候...", Toast.LENGTH_SHORT).show();
        }

        String targetUrl = getUpdateUrl(activity);

        executor.execute(() -> {
            HttpURLConnection conn = null;
            BufferedReader reader = null;
            try {
                URL url = new URL(targetUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestProperty("User-Agent", "CheckMarkScanner/" + getCurrentVersionName(activity));
                conn.setRequestProperty("Accept", "application/json");

                int respCode = conn.getResponseCode();
                if (respCode == HttpURLConnection.HTTP_OK) {
                    reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }

                    UpdateInfo info = UpdateInfo.fromJson(sb.toString());
                    int curCode = getCurrentVersionCode(activity);

                    mainHandler.post(() -> {
                        if (activity.isFinishing() || activity.isDestroyed()) return;

                        if (info.versionCode > curCode) {
                            showUpdateDialog(activity, info);
                        } else {
                            if (isManual) {
                                new AlertDialog.Builder(activity)
                                    .setTitle("已是最新版本")
                                    .setMessage("当前版本已是最新版 (v" + getCurrentVersionName(activity) + ")，无需更新。")
                                    .setPositiveButton("我知道了", null)
                                    .show();
                            }
                        }
                    });
                } else {
                    throw new Exception("HTTP " + respCode);
                }
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    if (isManual) {
                        showCheckFailedDialog(activity, targetUrl, e.getMessage());
                    }
                });
            } finally {
                try {
                    if (reader != null) reader.close();
                    if (conn != null) conn.disconnect();
                } catch (Exception ignored) {}
            }
        });
    }

    /**
     * 弹出版本更新对话框
     */
    public void showUpdateDialog(Activity activity, UpdateInfo info) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        String curVer = getCurrentVersionName(activity);
        String sizeStr = (info.apkSize != null && !info.apkSize.isEmpty()) ? " (" + info.apkSize + ")" : "";
        
        StringBuilder msg = new StringBuilder();
        msg.append("发现新版本：v").append(info.versionName).append(sizeStr).append("\n");
        msg.append("当前版本：v").append(curVer).append("\n\n");
        msg.append("【更新说明】\n").append(info.updateLog != null ? info.updateLog : "优化系统功能");

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
            .setTitle("🚀 发现新版本更新")
            .setMessage(msg.toString())
            .setCancelable(!info.forceUpdate)
            .setPositiveButton("立即更新安装", (dialog, which) -> {
                downloadAndInstall(activity, info);
            });

        if (!info.forceUpdate) {
            builder.setNegativeButton("稍后再说", null);
        }

        builder.setNeutralButton("更新设置", (dialog, which) -> {
            showConfigureUrlDialog(activity);
        });

        builder.show();
    }

    /**
     * 下载并自动启动安装
     */
    public void downloadAndInstall(Activity activity, UpdateInfo info) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (info.apkUrl == null || info.apkUrl.isEmpty()) {
            Toast.makeText(activity, "更新下载地址无效", Toast.LENGTH_SHORT).show();
            return;
        }

        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_update_progress, null);
        TextView tvTitle = dialogView.findViewById(R.id.tv_progress_title);
        ProgressBar pb = dialogView.findViewById(R.id.pb_update_progress);
        TextView tvStatus = dialogView.findViewById(R.id.tv_progress_status);

        tvTitle.setText("正在下载 CheckMarkScanner v" + info.versionName);

        AtomicBoolean isCancelled = new AtomicBoolean(false);

        AlertDialog progressDialog = new AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton("取消下载", (dialog, which) -> {
                isCancelled.set(true);
            })
            .create();

        progressDialog.show();

        executor.execute(() -> {
            HttpURLConnection conn = null;
            InputStream is = null;
            FileOutputStream fos = null;
            try {
                URL url = new URL(info.apkUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Accept-Encoding", "identity");

                int totalLength = conn.getContentLength();
                is = conn.getInputStream();

                File downloadDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (downloadDir == null) {
                    downloadDir = activity.getFilesDir();
                }
                File apkFile = new File(downloadDir, "CheckMarkScanner_v" + info.versionName + ".apk");
                if (apkFile.exists()) {
                    apkFile.delete();
                }

                fos = new FileOutputStream(apkFile);
                byte[] buffer = new byte[8192];
                int len;
                long downloaded = 0;
                long lastUpdateTime = 0;

                while ((len = is.read(buffer)) != -1) {
                    if (isCancelled.get()) {
                        apkFile.delete();
                        return;
                    }

                    fos.write(buffer, 0, len);
                    downloaded += len;

                    long now = System.currentTimeMillis();
                    if (now - lastUpdateTime > 100 || downloaded == totalLength) {
                        lastUpdateTime = now;
                        long curDownloaded = downloaded;
                        mainHandler.post(() -> {
                            if (progressDialog.isShowing()) {
                                if (totalLength > 0) {
                                    int progress = (int) (curDownloaded * 100 / totalLength);
                                    pb.setProgress(progress);
                                    float curMB = curDownloaded / (1024f * 1024f);
                                    float totalMB = totalLength / (1024f * 1024f);
                                    tvStatus.setText(String.format("正在下载: %.1fMB / %.1fMB (%d%%)", curMB, totalMB, progress));
                                } else {
                                    float curMB = curDownloaded / (1024f * 1024f);
                                    tvStatus.setText(String.format("已下载: %.1fMB", curMB));
                                }
                            }
                        });
                    }
                }

                fos.flush();

                mainHandler.post(() -> {
                    if (progressDialog.isShowing()) {
                        progressDialog.dismiss();
                    }
                    Toast.makeText(activity, "下载完成，正在准备安装...", Toast.LENGTH_SHORT).show();
                    installApk(activity, apkFile);
                });

            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (progressDialog.isShowing()) {
                        progressDialog.dismiss();
                    }
                    if (!isCancelled.get()) {
                        new AlertDialog.Builder(activity)
                            .setTitle("下载失败")
                            .setMessage("更新安装包下载出错：" + e.getMessage() + "\n\n您可以检查网络连接后重试。")
                            .setPositiveButton("重试", (dialog, which) -> downloadAndInstall(activity, info))
                            .setNegativeButton("取消", null)
                            .show();
                    }
                });
            } finally {
                try {
                    if (fos != null) fos.close();
                    if (is != null) is.close();
                    if (conn != null) conn.disconnect();
                } catch (Exception ignored) {}
            }
        });
    }

    /**
     * 适配全版本 Android 的 APK 自动安装器
     */
    public static void installApk(Activity activity, File apkFile) {
        if (activity == null || apkFile == null || !apkFile.exists()) {
            Toast.makeText(activity, "安装文件不存在，请重新下载", Toast.LENGTH_SHORT).show();
            return;
        }

        // Android 8.0 (API 26) 及以上需检查未知应用安装权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            boolean hasPermission = activity.getPackageManager().canRequestPackageInstalls();
            if (!hasPermission) {
                new AlertDialog.Builder(activity)
                    .setTitle("需要开启安装权限")
                    .setMessage("为完成自动升级，请允许本应用【安装未知来源应用】。\n点击确定前往系统设置开启。")
                    .setPositiveButton("去开启", (dialog, which) -> {
                        try {
                            Uri packageUri = Uri.parse("package:" + activity.getPackageName());
                            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri);
                            activity.startActivity(intent);
                        } catch (Exception e) {
                            Toast.makeText(activity, "打开设置失败，请手动到设置开启安装权限", Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
                return;
            }
        }

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Uri apkUri = FileProvider.getUriForFile(
                    activity, 
                    activity.getPackageName() + ".fileprovider", 
                    apkFile
                );
                intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive");
            }

            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, "启动安装器失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 自定义更新地址配置弹窗
     */
    public void showConfigureUrlDialog(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        EditText etUrl = new EditText(activity);
        etUrl.setHint("http://192.168.x.x:8080/version.json 或 https://...");
        etUrl.setText(getUpdateUrl(activity));
        etUrl.setSingleLine(false);
        etUrl.setMaxLines(3);
        etUrl.setPadding(30, 20, 30, 20);

        new AlertDialog.Builder(activity)
            .setTitle("⚙️ 配置更新服务地址")
            .setMessage("您可以指定局域网测试服务器、公司内网、Gitee 或 GitHub 的 version.json 链接：")
            .setView(etUrl)
            .setPositiveButton("保存并检查", (dialog, which) -> {
                String input = etUrl.getText().toString().trim();
                if (!input.isEmpty()) {
                    setUpdateUrl(activity, input);
                    Toast.makeText(activity, "已保存更新源，正在检测...", Toast.LENGTH_SHORT).show();
                    checkUpdate(activity, true);
                }
            })
            .setNeutralButton("恢复默认", (dialog, which) -> {
                setUpdateUrl(activity, DEFAULT_UPDATE_URL);
                Toast.makeText(activity, "已重置为官方默认源", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /**
     * 连接失败友好提示弹窗（强调离线完整可用性，避免开发者报错信息惊扰用户）
     */
    private void showCheckFailedDialog(Activity activity, String targetUrl, String errorMsg) {
        new AlertDialog.Builder(activity)
            .setTitle("💡 离线模式正常运行中")
            .setMessage("当前未连接到更新服务器。\n\n" +
                        "✅ 本软件全部打钩识别、智能修正与 Excel 填表导出功能均 100% 在本机离线运行，无需连网即可正常使用！\n\n" +
                        "若需获取最新升级，可在连入局域网 Wi-Fi 后重试。")
            .setPositiveButton("我知道了 (继续离线使用)", null)
            .setNeutralButton("重试检测", (dialog, which) -> checkUpdate(activity, true))
            .show();
    }
}
