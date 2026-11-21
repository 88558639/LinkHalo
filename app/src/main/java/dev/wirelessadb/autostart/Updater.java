package dev.wirelessadb.autostart;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * App-internal update, ported from OmniBar's Updater.kt to Java (no third-party deps).
 * Checks GitHub Releases for a newer APK, downloads it through mirror-accelerated URLs,
 * and hands the file to the system installer via {@link ApkProvider}.
 */
public final class Updater {
    private static final String TAG = "WirelessAdb";
    private static final String OWNER = "88558639";
    private static final String REPO = "WirelessAdb";

    /**
     * 镜像顺序：ghfast.top → gh-proxy.com → gh.llkk.cc → 直连。
     * 实测（2026-11-21）gh-proxy.com 响应最快（0.6–0.8s）、ghfast.top 次之，
     * ghproxy.net 已不可用（对 .atom 直接 403）故替换为 gh.llkk.cc。
     */
    private static final String[] MIRRORS = {
            "https://ghfast.top/",
            "https://gh-proxy.com/",
            "https://gh.llkk.cc/",
            ""
    };
    /** 检查更新阶段（不下载）的连接/读取超时：国内网络下 15s 太拖，8s 足够。 */
    private static final int CHECK_CONNECT_MS = 8000;
    private static final int CHECK_READ_MS = 8000;

    /** GitHub API 端点：直连 + 经 gh-proxy 代理（同一份 JSON，实测代理 200/1.1s）。 */
    private static final String API_LATEST =
            "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases/latest";
    private static final String API_VIA_PROXY = "https://gh-proxy.com/" + API_LATEST;

    public static final class UpdateInfo {
        public final String version;
        public final String apkUrl;
        public final String body;
        /** 远程 APK 的 sha256（小写十六进制）；为空表示未知，退回版本号比较。 */
        public final String sha256;

        UpdateInfo(String version, String apkUrl, String body, String sha256) {
            this.version = version;
            this.apkUrl = apkUrl;
            this.body = body;
            this.sha256 = sha256;
        }
    }

    private static volatile String lastError = null;
    private static volatile boolean cancelled = false;

    public static String getLastError() { return lastError; }
    public static void cancel() { cancelled = true; }
    public static boolean getCancelled() { return cancelled; }

    /**
     * 有新版本的条件（满足任一即算）：
     * 1) 远程版本号 > 已安装版本号；
     * 2) 版本号相同，但远程 APK 的 sha256 与本地已安装 APK 的 sha256 不同
     *    （同版本号重新出包的场景，例如当前要测的这个）。
     * 远程 sha 未知时只走条件 1，避免误报。
     */
    public static UpdateInfo check(Context context) {
        UpdateInfo latest = fetchLatest();
        if (latest == null) return null;
        String installed = installedVersion(context);
        boolean newerByVersion = versionKey(latest.version) > versionKey(installed);
        boolean sameVersionDifferentApk = false;
        if (!newerByVersion && latest.sha256 != null && !latest.sha256.isEmpty()) {
            String localSha = installedApkSha256(context);
            sameVersionDifferentApk = localSha != null
                    && !localSha.equalsIgnoreCase(latest.sha256);
        }
        boolean newer = newerByVersion || sameVersionDifferentApk;
        Log.i(TAG, "update check: installed=" + installed + " latest=" + latest.version
                + " remoteSha=" + latest.sha256 + " newerByVersion=" + newerByVersion
                + " sameVersionDifferentApk=" + sameVersionDifferentApk + " -> newer=" + newer);
        return newer ? latest : null;
    }

    /** 已安装 APK（base.apk）文件的 sha256，小写十六进制；失败返回 null。 */
    private static String installedApkSha256(Context context) {
        return sha256OfFile(new File(context.getPackageCodePath()));
    }

    /** 计算任意文件的 sha256，小写十六进制；失败返回 null。 */
    private static String sha256OfFile(File file) {
        if (file == null || !file.isFile()) return null;
        java.io.InputStream in = null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            in = new java.io.FileInputStream(file);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            Log.i(TAG, "sha256 failed: " + e.getMessage());
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * 清理已安装的更新包缓存。
     *
     * 仅当缓存里的 update.apk 与设备当前已安装 APK 的 sha256 完全一致时才删除——
     * 这说明该包确实已经装上，删除是安全的；若还没安装（或装的是别的包），
     * sha 不相等就不动它，避免误删正在安装流程中的文件。
     *
     * @return true 表示确实删掉了缓存文件
     */
    public static boolean cleanupInstalledApk(Context context) {
        File cached = new File(context.getCacheDir(), "apk/update.apk");
        if (!cached.isFile()) return false;
        String cachedSha = sha256OfFile(cached);
        if (cachedSha == null) return false;
        String installedSha = installedApkSha256(context);
        if (installedSha != null && installedSha.equalsIgnoreCase(cachedSha)) {
            boolean deleted = cached.delete();
            Log.i(TAG, "cleanup cached apk (already installed): deleted=" + deleted);
            return deleted;
        }
        return false;
    }

    /**
     * 检查最新版本。
     *
     * 参照 OmniBar 的做法：**直连 API 优先，被限流/劫持时改走 gh-proxy 代理同一 API**。
     * 代理返回的是同一份 JSON —— tag、body、asset digest 都有，因此降级时不再需要
     * 单独拼 APK 直链、也不需要另外抓 atom 取说明（那条路径只作为最后的兜底）。
     */
    private static UpdateInfo fetchLatest() {
        String[] endpoints = {API_LATEST, API_VIA_PROXY};
        for (String endpoint : endpoints) {
            UpdateInfo info = fetchLatestViaApi(endpoint);
            if (info != null) return info;
            Log.i(TAG, "check fallback: " + endpoint + " -> " + lastError);
        }
        // 两条 API 都不通时最后兜底：302 取 tag + atom 取说明。
        return fetchLatestViaRedirect();
    }

    private static UpdateInfo fetchLatestViaApi(String endpoint) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setConnectTimeout(CHECK_CONNECT_MS);
            conn.setReadTimeout(CHECK_READ_MS);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "WirelessAdb-Updater");
            int code = conn.getResponseCode();
            if (code != 200) {
                lastError = "HTTP " + code;
                return null;
            }
            String text;
            InputStream in = conn.getInputStream();
            try {
                byte[] buf = new byte[8192];
                StringBuilder sb = new StringBuilder();
                int n;
                while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
                text = sb.toString();
            } finally {
                in.close();
            }
            JSONObject json = new JSONObject(text);
            String tag = json.getString("tag_name").replaceFirst("^[vV]", "");
            String body = json.optString("body", "");
            String apkUrl = null;
            String apkSha = null;
            JSONArray assets = json.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    String name = a.getString("name");
                    if (name.endsWith(".apk")) {
                        apkUrl = a.getString("browser_download_url");
                        // GitHub 对新上传的 asset 会给出 digest（形如 "sha256:..."）。
                        String digest = a.optString("digest", "");
                        if (digest.startsWith("sha256:")) {
                            apkSha = digest.substring("sha256:".length());
                        }
                        break;
                    }
                }
            }
            lastError = null;
            return apkUrl != null ? new UpdateInfo(tag, apkUrl, body, apkSha) : null;
        } catch (Exception e) {
            lastError = e.getMessage();
            Log.i(TAG, "update check failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Fallback when the API is blocked (403 in CN): resolve tag via releases/latest 302. */
    private static UpdateInfo fetchLatestViaRedirect() {
        HttpURLConnection conn = null;
        String prevError = lastError;
        try {
            conn = (HttpURLConnection) new URL(
                    "https://github.com/" + OWNER + "/" + REPO + "/releases/latest").openConnection();
            conn.setConnectTimeout(CHECK_CONNECT_MS);
            conn.setReadTimeout(CHECK_READ_MS);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "WirelessAdb-Updater");
            conn.getResponseCode(); // 302
            String loc = conn.getHeaderField("Location");
            if (loc == null || !loc.contains("/tag/")) {
                lastError = prevError;
                return null;
            }
            String tag = loc.substring(loc.lastIndexOf("/tag/") + 5).trim();
            tag = tag.replaceFirst("^[vV]", "");
            if (tag.isEmpty() || !Character.isDigit(tag.charAt(0))) {
                lastError = prevError;
                return null;
            }
            String apkUrl = "https://github.com/" + OWNER + "/" + REPO
                    + "/releases/download/v" + tag + "/WirelessAdb-LSPosed-" + tag + ".apk";
            // 不再下载 .sha256 资产（发布已不再附带），避免每次检查都白跑 4 个死链接；
            // 远程 sha 只在 API 可用时由 asset 的 digest 字段提供。
            String notes = fetchReleaseNotes(tag);
            lastError = null;
            Log.i(TAG, "update check via redirect: tag=" + tag
                    + " notes=" + (notes == null ? 0 : notes.length()) + " apk=" + apkUrl);
            return new UpdateInfo(tag, apkUrl, notes == null ? "" : notes, null);
        } catch (Exception e) {
            Log.i(TAG, "redirect fallback failed: " + e.getMessage());
            lastError = prevError;
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 降级路径拿不到 API 的 body 字段，改从仓库的 releases.atom 取说明正文。
     *
     * Atom 源只列最近若干条，且第一条就是最新一版——所以即使用户跨好几个版本升级，
     * 展示的也只是最新这一版的说明，不需要（也不应该）把中间版本的日志拼起来。
     */
    private static String fetchReleaseNotes(String tag) {
        String path = "https://github.com/" + OWNER + "/" + REPO + "/releases.atom";
        // 镜像优先、直连兜底：实测 gh-proxy.com 对 .atom 返回 200（约 0.8s），
        // ghfast.top 对 .atom 是 403（跳过），国内直连 github.com 可能长时间无响应。
        String[] order = {MIRRORS[1], MIRRORS[2], ""};
        for (String mirror : order) {
            String url = mirror.isEmpty() ? path : mirror + path;
            String xml = downloadText(url, 262144);
            if (xml == null || !xml.contains("<entry>")) continue;
            String notes = atomEntryNotes(xml, tag);
            if (notes != null && !notes.isEmpty()) {
                Log.i(TAG, "notes from atom (" + (mirror.isEmpty() ? "direct" : mirror) + "): "
                        + notes.length() + " chars");
                return notes;
            }
        }
        return "";
    }

    /** 在 atom 里找 tag 对应的 entry（找不到退回首条），把 content 的 HTML 转成纯文本。 */
    private static String atomEntryNotes(String xml, String tag) {
        String first = null;
        int from = 0;
        while (true) {
            int start = xml.indexOf("<entry>", from);
            if (start < 0) break;
            int end = xml.indexOf("</entry>", start);
            if (end < 0) break;
            String entry = xml.substring(start, end);
            from = end + 8;
            String content = between(entry, "<content", "</content>");
            if (content == null) continue;
            int gt = content.indexOf('>');
            if (gt < 0) continue;
            content = content.substring(gt + 1);
            if (first == null) first = content;
            // atom 的 <title> 是 release 名称（如「1.2.29 更新包安装后自动清理缓存」），
            // 真正的 tag 在 <link href=".../releases/tag/v1.2.29"/> 里。
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("/releases/tag/([^\"'/]+)").matcher(entry);
            if (m.find() && m.group(1).replaceFirst("^[vV]", "").equals(tag)) {
                return htmlToText(content);
            }
        }
        return first == null ? "" : htmlToText(first);
    }

    private static String between(String s, String open, String close) {
        int a = s.indexOf(open);
        if (a < 0) return null;
        a += open.length();
        int b = s.indexOf(close, a);
        if (b < 0) return null;
        return s.substring(a, b);
    }

    /** 说明正文是 HTML（atom 的 content），只做够用的降级：段落/列表换行、去标签、还原实体。 */
    private static String htmlToText(String html) {
        // 顺序关键：atom 的 content 是「HTML 再被 XML 转义」一层，必须先还原实体，
        // 才能看到真正的 <p>/<li> 标签再剥掉；反过来做会把 &lt;p&gt; 原样留在正文里。
        String s = unescapeHtml(html).replace("\r\n", "\n");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>", "\n")
                .replaceAll("(?i)<li[^>]*>", "• ")
                .replaceAll("(?i)</li>", "\n")
                .replaceAll("(?i)</h[1-6]>", "\n")
                .replaceAll("<[^>]+>", "");
        // 只保留单换行：HTML 里标签之间的缩进换行会把列表撑成一行一句的稀疏版式。
        s = s.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{2,}", "\n");
        return s.trim();
    }

    private static String unescapeHtml(String s) {
        String out = s.replace("&nbsp;", " ")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("&#(x?)([0-9a-fA-F]+);").matcher(out);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int cp;
            try {
                cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
            } catch (Exception e) {
                cp = '?';
            }
            if (cp < 1 || cp > 0x10FFFF) cp = '?';
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                    new String(Character.toChars(cp))));
        }
        m.appendTail(sb);
        return sb.toString().replace("&amp;", "&");
    }

    private static String downloadText(String url) {
        return downloadText(url, 4096);
    }

    private static String downloadText(String url, int limit) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "WirelessAdb-Updater");
            if (conn.getResponseCode() != 200) return null;
            InputStream in = conn.getInputStream();
            try {
                byte[] buf = new byte[4096];
                StringBuilder sb = new StringBuilder();
                int n;
                while ((n = in.read(buf)) > 0 && sb.length() < limit) {
                    sb.append(new String(buf, 0, n, "UTF-8"));
                }
                return sb.toString();
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Downloads through the mirror list, reporting 0..100. Returns true on success. */
    public static boolean download(UpdateInfo info, File outFile, ProgressCallback cb) {
        cancelled = false;
        File parent = outFile.getParentFile();
        if (parent != null) parent.mkdirs();
        for (String mirror : MIRRORS) {
            if (cancelled) return false;
            String url = mirror.isEmpty() ? info.apkUrl : mirror + info.apkUrl;
            Log.i(TAG, "update download try: " + url);
            if (downloadSingle(url, outFile, cb)) return true;
            if (cancelled) return false;
        }
        return false;
    }

    private static boolean downloadSingle(String url, File outFile, ProgressCallback cb) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "WirelessAdb-Updater");
            if (conn.getResponseCode() != 200) return false;
            long total = conn.getContentLengthLong();
            InputStream in = conn.getInputStream();
            OutputStream out = new java.io.FileOutputStream(outFile);
            try {
                byte[] buf = new byte[8192];
                long got = 0;
                int last = -1;
                while (true) {
                    if (cancelled) return false;
                    int n = in.read(buf);
                    if (n < 0) break;
                    out.write(buf, 0, n);
                    got += n;
                    if (total > 0) {
                        int pct = (int) ((got * 100) / total);
                        if (pct != last) {
                            last = pct;
                            if (cb != null) cb.onProgress(pct);
                        }
                    }
                }
            } finally {
                out.close();
                in.close();
            }
            return true;
        } catch (Exception e) {
            Log.i(TAG, "download failed: " + url + " " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Hands the downloaded APK to the system installer via ApkProvider. */
    public static void install(Context context) {
        Uri uri = Uri.parse("content://" + context.getPackageName() + ".apk/update.apk");
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(intent);
    }

    public static boolean canInstall(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || context.getPackageManager().canRequestPackageInstalls();
    }

    public static void openInstallPermission(Context context) {
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        context.startActivity(intent);
    }

    /** Auto-check limited to once per half-day (am/pm). */
    public static boolean shouldAutoCheck(Context context) {
        android.content.SharedPreferences p = context.getSharedPreferences("update", Context.MODE_PRIVATE);
        String today = new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date());
        String slot = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < 12 ? "am" : "pm";
        return !today.equals(p.getString("date", "")) || !slot.equals(p.getString("slot", ""));
    }

    public static void markAutoChecked(Context context) {
        android.content.SharedPreferences p = context.getSharedPreferences("update", Context.MODE_PRIVATE);
        String today = new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date());
        String slot = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < 12 ? "am" : "pm";
        p.edit().putString("date", today).putString("slot", slot).apply();
    }

    private static String installedVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /** "1.2.26" -> 10226; sorts numerically. Unparseable -> -1 (always older). */
    private static long versionKey(String v) {
        if (v == null) return -1;
        String[] parts = v.trim().split("\\.");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            return (long) major * 10000 + minor * 100 + patch;
        } catch (Exception e) {
            return -1;
        }
    }

    public interface ProgressCallback {
        void onProgress(int percent);
    }
}
