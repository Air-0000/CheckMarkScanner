package com.parking.checkscanner;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 更新源与安装包下载地址解析 (纯 Java，无 Android 依赖)：
 * 1. 按「用户自定义 → 局域网 → 公网 Funnel」顺序生成待检测的 version.json 地址
 * 2. 安装包优先从刚检测成功的那台服务器下载，再回退到 version.json 里写的 apkUrl，
 *    这样 apkUrl 写成局域网地址时外网也能下载，写成公网地址时局域网内仍走内网
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
     * 安装包下载候选地址：
     * 1. 检测成功的服务器上与 version.json 同目录的同名安装包 (该服务器刚刚可达)
     * 2. version.json 中的 apkUrl (相对路径按 sourceUrl 解析)
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
            if (!fileName.isEmpty()) {
                addIfValid(candidates, new URL(source, fileName).toString());
            }
            addIfValid(candidates, resolved.toString());
        } catch (Exception e) {
            addIfValid(candidates, apk);
        }
        return candidates;
    }

    /**
     * 是否为早期版本遗留、现已失效的更新地址 (需重置为默认)。
     * 按主机名精确比较：旧实现 contains("10.0.0.1") 会误伤 10.0.0.192 等 10.0.0.1xx 地址。
     */
    public static boolean isLegacyUrl(String url) {
        if (url == null || url.trim().isEmpty() || !url.trim().startsWith("http")) return true;
        if (url.contains("githubusercontent.com")) return true;
        try {
            URL u = new URL(url.trim());
            return "10.0.0.1".equals(u.getHost()) || u.getPort() == 5005;
        } catch (Exception e) {
            return true;
        }
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
