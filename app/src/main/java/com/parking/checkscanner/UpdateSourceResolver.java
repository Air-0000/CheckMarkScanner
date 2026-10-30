package com.parking.checkscanner;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 更新源与安装包下载地址解析 (纯 Java，无 Android 依赖)：
 * 1. 按「用户自定义 → 局域网 → 国内中转 → 公网 Funnel → Gitee」的优先级生成 version.json 地址，
 *    检测时同时请求，按优先级与等待窗口选用 (pickSource)
 * 2. 安装包：从局域网检测成功时走局域网；否则先走 version.json 里的 apkUrl
 *    (服务器按来源给外网请求下发最快的国内中转地址)，失败再回退到检测成功的那台服务器
 */
public final class UpdateSourceResolver {

    private UpdateSourceResolver() {}

    /**
     * 待检测的更新源列表 (去重保序，忽略空值与非 http 地址)
     */
    public static List<String> buildSources(String customUrl, String... builtInUrls) {
        List<String> sources = new ArrayList<>();
        addIfValid(sources, customUrl);
        if (builtInUrls != null) {
            for (String url : builtInUrls) {
                addIfValid(sources, url);
            }
        }
        return sources;
    }

    /**
     * 安装包下载候选地址 (sameHost = 检测成功的服务器上与 version.json 同目录的同名安装包)：
     * - 从局域网检测成功：sameHost (内网最快) → apkUrl
     * - 其他情况：apkUrl (服务器下发的国内中转) → sameHost (刚刚可达的服务器兜底)
     * apkUrl 为相对路径时按 sourceUrl 解析
     */
    public static List<String> buildDownloadCandidates(String sourceUrl, String apkUrl) {
        List<String> candidates = new ArrayList<>();
        if (apkUrl == null || apkUrl.trim().isEmpty()) return candidates;
        String apk = apkUrl.trim();
        try {
            URL source = new URL(sourceUrl);
            URL resolved = new URL(source, apk);
            String path = resolved.getPath();
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            String sameHost = fileName.isEmpty() ? null : new URL(source, fileName).toString();
            if (isPrivateHost(sourceUrl)) {
                addIfValid(candidates, sameHost);
                addIfValid(candidates, resolved.toString());
            } else {
                addIfValid(candidates, resolved.toString());
                addIfValid(candidates, sameHost);
            }
        } catch (Exception e) {
            addIfValid(candidates, apk);
        }
        return candidates;
    }

    /**
     * 是否为早期版本遗留、现已失效的更新地址 (需重置为默认)。
     * 早期 5005 端口的更新服务已停用；10.0.0.1 (路由器) 自 2.7.1 起在 8080 端口提供更新，不再算失效地址。
     */
    public static boolean isLegacyUrl(String url) {
        if (url == null || url.trim().isEmpty() || !url.trim().startsWith("http")) return true;
        if (url.contains("githubusercontent.com")) return true;
        try {
            URL u = new URL(url.trim());
            return u.getPort() == 5005;
        } catch (Exception e) {
            return true;
        }
    }

    // 同时请求各更新源时，单个源的请求状态
    public static final int STATE_PENDING = 0;
    public static final int STATE_OK = 1;
    public static final int STATE_FAILED = 2;
    // pickSource 的返回值：继续等待 / 全部失败
    public static final int PICK_WAIT = -1;
    public static final int PICK_NONE = -2;
    // 优先级更高的源仍在请求中时，最多等它多久：局域网 1 秒 (内网正常远快于此)，其他源 4 秒
    public static final long LAN_GRACE_MS = 1000;
    public static final long REMOTE_GRACE_MS = 4000;

    /**
     * 同时请求所有更新源时决定用哪个 (sources 按优先级排列：自定义 → 局域网 → 国内中转 → Funnel → Gitee)。
     * 某个更优先的源仍在请求中且未超过它的等待窗口时继续等待；否则取已成功的源中优先级最高的。
     * 例：局域网 1 秒内回应就用局域网，否则优先中转 (4 秒内)，再 Funnel。
     *
     * @return 选中的下标，或 PICK_WAIT (继续等待)、PICK_NONE (全部失败)
     */
    public static int pickSource(List<String> sources, int[] states, long elapsedMs) {
        boolean anyPending = false;
        for (int i = 0; i < sources.size(); i++) {
            if (states[i] == STATE_OK) return i;
            if (states[i] == STATE_PENDING) {
                if (elapsedMs < graceFor(sources.get(i))) return PICK_WAIT;
                anyPending = true;
            }
        }
        return anyPending ? PICK_WAIT : PICK_NONE;
    }

    /**
     * 距离下一个等待窗口到期还有多久 (pickSource 的结论可能随之改变)；没有未到期的窗口时返回 maxMs
     */
    public static long nextDecisionDelay(List<String> sources, int[] states, long elapsedMs, long maxMs) {
        long delay = maxMs;
        for (int i = 0; i < sources.size(); i++) {
            long grace = graceFor(sources.get(i));
            if (states[i] == STATE_PENDING && elapsedMs < grace) {
                delay = Math.min(delay, grace - elapsedMs);
            }
        }
        return Math.max(1, delay);
    }

    private static long graceFor(String url) {
        return isPrivateHost(url) ? LAN_GRACE_MS : REMOTE_GRACE_MS;
    }

    /**
     * 两个地址是否指向同一位置：协议与主机名不区分大小写，省略的端口按协议默认端口，空路径视为 "/"
     */
    public static boolean isSameUrl(String a, String b) {
        if (a == null || b == null) return false;
        try {
            URL x = new URL(a.trim());
            URL y = new URL(b.trim());
            return x.getProtocol().equalsIgnoreCase(y.getProtocol())
                && x.getHost().equalsIgnoreCase(y.getHost())
                && effectivePort(x) == effectivePort(y)
                && normalizedPath(x).equals(normalizedPath(y))
                && Objects.equals(x.getQuery(), y.getQuery());
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean matchesAny(String url, String... candidates) {
        if (candidates == null) return false;
        for (String candidate : candidates) {
            if (isSameUrl(url, candidate)) return true;
        }
        return false;
    }

    private static int effectivePort(URL u) {
        return u.getPort() != -1 ? u.getPort() : u.getDefaultPort();
    }

    private static String normalizedPath(URL u) {
        String path = u.getPath();
        return path.isEmpty() ? "/" : path;
    }

    /**
     * 是否为局域网私有地址 (10.x / 172.16~31.x / 192.168.x)：外网时这类地址必然连不上，
     * 调用方据此使用更短的连接超时，尽快切换到公网源
     */
    public static boolean isPrivateHost(String url) {
        try {
            String host = new URL(url).getHost();
            if (host.startsWith("10.") || host.startsWith("192.168.")) return true;
            if (host.startsWith("172.")) {
                String[] parts = host.split("\\.");
                int second = Integer.parseInt(parts[1]);
                return second >= 16 && second <= 31;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static void addIfValid(List<String> list, String url) {
        if (url == null) return;
        String u = url.trim();
        if (u.isEmpty() || !u.startsWith("http") || list.contains(u)) return;
        list.add(u);
    }
}
