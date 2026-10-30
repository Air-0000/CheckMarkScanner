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
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
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
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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

    // 局域网更新源：岗亭内网 Wi-Fi 下最快 (路由器上的更新服务)
    public static final String DEFAULT_UPDATE_URL =
        "http://10.0.0.1:8080/version.json";

    // 国内中转更新源：路由器经樱花 frp 国内节点 (长沙电信) 暴露的 HTTPS 地址，
    // 证书为公共 CA 签发。手机不在局域网时优先使用，不必绕道海外
    public static final String RELAY_UPDATE_URL =
        "https://www.haa95d85b.nyat.app:19846/version.json";

    // 公网兜底更新源：路由器经 Tailscale Funnel 暴露的 HTTPS 地址 (入口在海外，较慢)，国内中转不可用时使用
    public static final String PUBLIC_UPDATE_URL =
        "https://arthur.taild05f40.ts.net:8443/version.json";

    // 2.7.0 及以前更新服务在台式机上，旧版本存下的这些地址已失效，当作内置地址处理 (重置为默认)
    private static final String OLD_DESKTOP_LAN_URL =
        "http://10.0.0.192:8080/version.json";
    private static final String OLD_DESKTOP_PUBLIC_URL =
        "https://constant-b850m-eagle-wifi6e-ice.taild05f40.ts.net:8443/version.json";
    // 定 Funnel 端口前曾给出的不带 :8443 的地址 (443 仅 tailnet 可见，公网连不上)
    private static final String MISTAKEN_PUBLIC_URL =
        "https://constant-b850m-eagle-wifi6e-ice.taild05f40.ts.net/version.json";

    // 离线兜底更新源：Gitee 公开仓库里的 version.json (国内访问快，安装包放在该仓库的发行版附件)。
    // 发版时手动同步，可能落后于路由器，所以排在最后，只在路由器三条线路都连不上时使用
    public static final String GITEE_UPDATE_URL =
        "https://gitee.com/Air-0000/checkmark-update/raw/master/version.json";

    // 局域网地址在外网必然连不上，用短超时尽快切到公网源
    private static final int LAN_CONNECT_TIMEOUT_MS = 3000;
    private static final int PUBLIC_CONNECT_TIMEOUT_MS = 8000;
    // 同时检测各更新源的总时限 (单个源的连接 + 读取超时之和以内)
    private static final long RACE_MAX_MS = 15000;

    private static final String TAG = "AppUpdateManager";

    private static AppUpdateManager instance;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    // 检测更新时同时请求各更新源用的线程池
    private final ExecutorService raceExecutor = Executors.newCachedThreadPool();
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
        public String sha256; // 安装包 SHA-256 (十六进制)，为空时不校验
        public String sourceUrl; // 本次检测成功的 version.json 地址，决定安装包从哪条线路下载

        public static UpdateInfo fromJson(String jsonStr) throws Exception {
            JSONObject json = new JSONObject(jsonStr);
            UpdateInfo info = new UpdateInfo();
            info.versionCode = json.optInt("versionCode", 0);
            info.versionName = json.optString("versionName", "");
            info.apkUrl = json.optString("apkUrl", "");
            info.apkSize = json.optString("apkSize", "");
            info.updateLog = json.optString("updateLog", "优化系统性能并修复若干已知问题。");
            info.forceUpdate = json.optBoolean("forceUpdate", false);
            info.sha256 = json.optString("sha256", "").trim();
            return info;
        }
    }

    public static String getUpdateUrl(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String url = sp.getString(KEY_UPDATE_URL, DEFAULT_UPDATE_URL);
        // 填的若是内置地址 (旧版本在外网需手动填一次中转 / Funnel 地址)，一律回到默认顺序
        // 局域网 → 中转 → Funnel → Gitee，避免在局域网里也先走中转或海外线路
        boolean builtIn = UpdateSourceResolver.matchesAny(url,
            DEFAULT_UPDATE_URL, RELAY_UPDATE_URL, PUBLIC_UPDATE_URL, GITEE_UPDATE_URL,
            OLD_DESKTOP_LAN_URL, OLD_DESKTOP_PUBLIC_URL, MISTAKEN_PUBLIC_URL);
        if (UpdateSourceResolver.isLegacyUrl(url) || (builtIn && !DEFAULT_UPDATE_URL.equals(url))) {
            url = DEFAULT_UPDATE_URL;
            sp.edit().putString(KEY_UPDATE_URL, url).apply();
        }
        return url;
    }

    /**
     * 依次尝试的更新源：用户在「更新服务器设置」里指定的地址 → 局域网 → 国内中转 → 公网 Funnel → Gitee
     */
    public static List<String> getUpdateSources(Context context) {
        return UpdateSourceResolver.buildSources(getUpdateUrl(context),
            DEFAULT_UPDATE_URL, RELAY_UPDATE_URL, PUBLIC_UPDATE_URL, GITEE_UPDATE_URL);
    }

    private static int connectTimeoutFor(String url) {
        return UpdateSourceResolver.isPrivateHost(url) ? LAN_CONNECT_TIMEOUT_MS : PUBLIC_CONNECT_TIMEOUT_MS;
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
            StyledToast.show(activity, "正在连接更新服务器，请稍候...", Toast.LENGTH_SHORT);
        }

        List<String> sources = getUpdateSources(activity);

        executor.execute(() -> {
            StringBuilder errors = new StringBuilder();
            final UpdateInfo info = raceUpdateSources(activity, sources, errors);
            mainHandler.post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;

                if (info == null) {
                    if (isManual) {
                        showCheckFailedDialog(activity, sources.toString(), errors.toString());
                    }
                } else if (info.versionCode > getCurrentVersionCode(activity)) {
                    showUpdateDialog(activity, info);
                } else if (isManual) {
                    new AlertDialog.Builder(activity)
                        .setTitle("已是最新版本")
                        .setMessage("当前版本已是最新版 (v" + getCurrentVersionName(activity) + ")，无需更新。")
                        .setPositiveButton("我知道了", null)
                        .show();
                }
            });
        });
    }

    /**
     * 同时向所有更新源请求 version.json，按优先级选用 (规则见 UpdateSourceResolver.pickSource)：
     * 局域网 1 秒内回应就用局域网，否则优先国内中转，再 Funnel，路由器都连不上时用 Gitee。
     * 在后台线程调用；全部失败返回 null，失败原因追加到 errors。未被选中的请求各自超时结束，结果丢弃。
     */
    private UpdateInfo raceUpdateSources(Context context, List<String> sources, StringBuilder errors) {
        int n = sources.size();
        int[] states = new int[n];
        UpdateInfo[] infos = new UpdateInfo[n];
        String[] failures = new String[n];
        BlockingQueue<Integer> finished = new LinkedBlockingQueue<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            raceExecutor.execute(() -> {
                try {
                    infos[idx] = fetchUpdateInfo(context, sources.get(idx));
                } catch (Exception e) {
                    failures[idx] = e.getMessage();
                }
                finished.offer(idx);
            });
        }

        long start = SystemClock.elapsedRealtime();
        while (true) {
            long elapsed = SystemClock.elapsedRealtime() - start;
            int pick = UpdateSourceResolver.pickSource(sources, states, elapsed);
            if (pick >= 0) {
                Log.i(TAG, "更新源选用 " + sources.get(pick) + "，用时 " + elapsed + "ms，状态 " + Arrays.toString(states));
                return infos[pick];
            }
            if (pick == UpdateSourceResolver.PICK_NONE || elapsed > RACE_MAX_MS) {
                for (int i = 0; i < n; i++) {
                    errors.append(sources.get(i)).append(" -> ")
                          .append(states[i] == UpdateSourceResolver.STATE_FAILED ? failures[i] : "超时").append('\n');
                }
                Log.w(TAG, "所有更新源均不可用，用时 " + elapsed + "ms:\n" + errors);
                return null;
            }
            long wait = UpdateSourceResolver.nextDecisionDelay(sources, states, elapsed, RACE_MAX_MS - elapsed);
            try {
                Integer idx = finished.poll(wait, TimeUnit.MILLISECONDS);
                if (idx != null) {
                    states[idx] = (infos[idx] != null) ? UpdateSourceResolver.STATE_OK : UpdateSourceResolver.STATE_FAILED;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /**
     * 从单个更新源读取 version.json (在后台线程调用)，失败抛出异常
     */
    private UpdateInfo fetchUpdateInfo(Context context, String sourceUrl) throws Exception {
        HttpURLConnection conn = null;
        BufferedReader reader = null;
        try {
            conn = (HttpURLConnection) new URL(sourceUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(connectTimeoutFor(sourceUrl));
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "CheckMarkScanner/" + getCurrentVersionName(context));
            conn.setRequestProperty("Accept", "application/json");

            int respCode = conn.getResponseCode();
            if (respCode != HttpURLConnection.HTTP_OK) {
                throw new Exception("HTTP " + respCode);
            }
            reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }

            UpdateInfo info = UpdateInfo.fromJson(sb.toString());
            info.sourceUrl = sourceUrl;
            return info;
        } finally {
            try {
                if (reader != null) reader.close();
                if (conn != null) conn.disconnect();
            } catch (Exception ignored) {}
        }
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

        // 强制更新时不给「更新设置」：点它会关掉本弹窗，等于绕过强制更新
        if (!info.forceUpdate) {
            builder.setNeutralButton("更新设置", (dialog, which) -> {
                showConfigureUrlDialog(activity);
            });
        }

        builder.show();
    }

    /**
     * 下载并自动启动安装
     */
    public void downloadAndInstall(Activity activity, UpdateInfo info) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (info.apkUrl == null || info.apkUrl.isEmpty()) {
            StyledToast.show(activity, "更新下载地址无效", Toast.LENGTH_SHORT);
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
                // 强制更新不能取消了事，回到更新弹窗
                if (info.forceUpdate) showUpdateDialog(activity, info);
            })
            .create();

        progressDialog.show();

        String sourceUrl = (info.sourceUrl != null) ? info.sourceUrl : getUpdateUrl(activity);
        List<String> candidates = UpdateSourceResolver.buildDownloadCandidates(sourceUrl, info.apkUrl);

        executor.execute(() -> {
            HttpURLConnection conn = null;
            InputStream is = null;
            FileOutputStream fos = null;
            try {
                conn = openApkConnection(candidates);

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
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
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
                    digest.update(buffer, 0, len);
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

                // 连接中途断开时读取也可能正常结束，残缺的安装包不能交给系统安装器
                if (totalLength > 0 && downloaded != totalLength) {
                    apkFile.delete();
                    throw new Exception(String.format("安装包下载不完整 (%d / %d 字节)，网络可能中断", downloaded, totalLength));
                }
                if (info.sha256 != null && !info.sha256.isEmpty()
                        && !info.sha256.equalsIgnoreCase(toHex(digest.digest()))) {
                    apkFile.delete();
                    throw new Exception("安装包校验失败 (SHA-256 不一致)，文件可能损坏或被篡改");
                }

                mainHandler.post(() -> {
                    if (progressDialog.isShowing()) {
                        progressDialog.dismiss();
                    }
                    StyledToast.show(activity, "下载完成，正在准备安装...", Toast.LENGTH_SHORT);
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
                            .setNegativeButton("取消", (dialog, which) -> {
                                if (info.forceUpdate) showUpdateDialog(activity, info);
                            })
                            .setCancelable(!info.forceUpdate)
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

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 按候选顺序连接安装包地址，返回第一个 HTTP 200 的连接 (在后台线程调用)。
     * 非 200 (如 404) 直接换下一个，避免把错误页当安装包写入。
     */
    private HttpURLConnection openApkConnection(List<String> candidates) throws Exception {
        StringBuilder errors = new StringBuilder();
        for (String candidate : candidates) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(candidate).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(connectTimeoutFor(candidate));
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Accept-Encoding", "identity");
                int respCode = conn.getResponseCode();
                if (respCode == HttpURLConnection.HTTP_OK) {
                    return conn;
                }
                errors.append("HTTP ").append(respCode);
            } catch (Exception e) {
                errors.append(e.getMessage());
            }
            errors.append(" (").append(candidate).append(")\n");
            if (conn != null) conn.disconnect();
        }
        throw new Exception("所有下载地址均不可用：\n" + errors.toString().trim());
    }

    /**
     * 适配全版本 Android 的 APK 自动安装器
     */
    public static void installApk(Activity activity, File apkFile) {
        if (activity == null || apkFile == null || !apkFile.exists()) {
            StyledToast.show(activity, "安装文件不存在，请重新下载", Toast.LENGTH_SHORT);
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
                            StyledToast.show(activity, "打开设置失败，请手动到设置开启安装权限", Toast.LENGTH_LONG);
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
            StyledToast.show(activity, "启动安装器失败: " + e.getMessage(), Toast.LENGTH_LONG);
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

        // 输入框左右留白，与对话框正文对齐，不贴着对话框描边
        FrameLayout urlContainer = new FrameLayout(activity);
        int sidePadding = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        urlContainer.setPadding(sidePadding, 0, sidePadding, 0);
        urlContainer.addView(etUrl);

        new AlertDialog.Builder(activity)
            .setTitle("⚙️ 配置更新服务地址")
            .setMessage("默认同时检测局域网服务器、国内中转、公网备用地址和 Gitee，优先用局域网，其次国内中转，服务器都连不上时用 Gitee。\n如需指定其他 version.json 地址 (会最优先使用)，可在此填写：")
            .setView(urlContainer)
            .setPositiveButton("保存并检查", (dialog, which) -> {
                String input = etUrl.getText().toString().trim();
                if (!input.isEmpty()) {
                    setUpdateUrl(activity, input);
                    StyledToast.show(activity, "已保存更新源，正在检测...", Toast.LENGTH_SHORT);
                    checkUpdate(activity, true);
                }
            })
            .setNeutralButton("恢复默认", (dialog, which) -> {
                setUpdateUrl(activity, DEFAULT_UPDATE_URL);
                StyledToast.show(activity, "已重置为官方默认源", Toast.LENGTH_SHORT);
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
                        "局域网与公网更新服务器都暂时连不上，请检查手机网络后重试。")
            .setPositiveButton("我知道了 (继续离线使用)", null)
            .setNeutralButton("重试检测", (dialog, which) -> checkUpdate(activity, true))
            .show();
    }
}
