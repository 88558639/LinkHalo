package io.github.linkhalo;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StatusActivity extends Activity {
    private static final int LOG_PREVIEW_LINES = 12;
    private static final Pattern ADDRESS_IN_LOG =
            Pattern.compile("(\\d{1,3}(?:\\.\\d{1,3}){3}:\\d{1,5})");
    /** 模块日志里的「[端口] TLS 端口=N」，是当前 adbd 实例的真实端口。 */
    private static final Pattern TLS_PORT_IN_LOG =
            Pattern.compile("\\[端口\\]\\s*TLS 端口=(\\d+)");

    private TextView noticeChip;
    private TextView statusTitle;
    private TextView modeLabel;
    private TextView addressView;
    private EditText portInput;
    private View modeTlsBtn;
    private View modeTcpBtn;
    private ImageView modeTlsIcon;
    private ImageView modeTcpIcon;
    private TextView modeTlsText;
    private TextView modeTcpText;
    private View logEmpty;
    private ScrollView logScroll;
    private TextView logView;
    private View heroAction;
    private ImageView heroIcon;
    /** 切模式后的过渡态：目标模式 + 切换前的 TLS 端口；直到新端口出现或超时才结束。 */
    private String switchingMode = null;
    private long switchingStartedAt = 0L;
    private int oldTlsPortOnSwitch = -1;
    private static final long SWITCH_TIMEOUT_MS = 12_000L;

    private ScrollView scroll;

    // app 内更新
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService updateExec = Executors.newSingleThreadExecutor();
    private volatile boolean checkingUpdate = false;
    private Dialog downloadDialog;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_status);
        applySystemInsets();
        bindViews();
        wireActions();
        // 不预置文案：首帧就按真实状态判定，避免冷启动瞬间两个「就绪」同时亮。
        refreshAll(null);
        cleanupUpdateCache();
    }

    /** 整页自由滚动：滚动区吃状态栏/导航栏留白。 */
    private void applySystemInsets() {
        scroll = findViewById(R.id.scroll);
        int extraTop = dp(12);
        int bottomPad = dp(20);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            v.setPadding(v.getPaddingLeft(), top + extraTop,
                    v.getPaddingRight(), bottom + bottomPad);
            return insets.consumeSystemWindowInsets();
        });
        scroll.requestApplyInsets();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshAll(null);
        // 从系统安装器返回时 onResume 触发：此时安装已完成，正好清理缓存里的更新包。
        cleanupUpdateCache();
    }

    /**
     * 清理已安装的更新包缓存。
     * 只在「缓存包 sha == 已安装 APK sha」时删除（Updater 内部判断），
     * 因此不会误删尚未安装、或正在下载写入中的文件。
     */
    private void cleanupUpdateCache() {
        final File cached = new File(getCacheDir(), "apk/update.apk");
        if (!cached.isFile()) return;
        final long sizeKb = Math.max(1, cached.length() / 1024);
        updateExec.execute(() -> {
            if (!Updater.cleanupInstalledApk(this)) return;
            String ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
                    .format(new java.util.Date());
            EventLog.append(this, ts + "  已清理更新缓存（更新包已安装，释放 " + sizeKb + " KB）");
            uiHandler.post(() -> refreshAll(null));
        });
    }

    private void bindViews() {
        noticeChip = findViewById(R.id.notice_chip);
        statusTitle = findViewById(R.id.status_title);
        modeLabel = findViewById(R.id.mode_label);
        addressView = findViewById(R.id.address_view);
        portInput = findViewById(R.id.port_input);
        modeTlsBtn = findViewById(R.id.mode_tls_btn);
        modeTcpBtn = findViewById(R.id.mode_tcp_btn);
        modeTlsIcon = findViewById(R.id.mode_tls_icon);
        modeTcpIcon = findViewById(R.id.mode_tcp_icon);
        modeTlsText = findViewById(R.id.mode_tls_text);
        modeTcpText = findViewById(R.id.mode_tcp_text);
        logEmpty = findViewById(R.id.log_empty);
        logScroll = findViewById(R.id.log_scroll);
        logView = findViewById(R.id.log_view);
        heroAction = findViewById(R.id.hero_action);
        heroIcon = findViewById(R.id.hero_icon);

        // 地址整行点按即复制：这是唯一无条件立即写剪贴板的入口。
        addressView.setOnClickListener(v -> {
            if (!AdbModeConfig.isEnabled(this)) {
                Toast.makeText(this, "无线 ADB 已关闭", Toast.LENGTH_SHORT).show();
                return;
            }
            v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(70)
                    .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f)
                            .setDuration(70).start())
                    .start();
            sendBroadcast(new Intent("io.github.linkhalo.REQUEST_COPY").setPackage("android"));
            Toast.makeText(this, "地址已复制", Toast.LENGTH_SHORT).show();
        });

        // 圆形开关：控制「模块是否接管无线 ADB」。状态跨重启持久（存 Settings.Global）。
        heroAction.setOnClickListener(v -> {
            boolean target = !AdbModeConfig.isEnabled(this);
            v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80)
                    .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f)
                            .setDuration(80).start())
                    .start();
            // App 侧写一次（通常没有 WRITE_SECURE_SETTINGS，可能失败）；
            // 广播里 hook 在 system_server 内有权限，会再写一次，以那边为准。
            AdbModeConfig.setEnabled(this, target);
            sendBroadcast(new Intent("io.github.linkhalo.SET_ENABLED")
                    .setPackage("android")
                    .putExtra("enabled", target));
            syncHero(target);
            Toast.makeText(this, target ? "无线 ADB 已开启" : "无线 ADB 已关闭",
                    Toast.LENGTH_SHORT).show();
            // 关 adbd 比开更快，但 TCP 要重启 adbd，两边都留几次刷新。
            portInput.postDelayed(() -> refreshAll(null), 1200);
            portInput.postDelayed(() -> refreshAll(null), 3500);
        });

        findViewById(R.id.log_refresh).setOnClickListener(v -> refreshAll("记录已刷新"));
    }

    private void wireActions() {
        modeTlsBtn.setOnClickListener(v -> switchMode(AdbModeConfig.MODE_TLS));
        modeTcpBtn.setOnClickListener(v -> switchMode(AdbModeConfig.MODE_TCP));
        portInput.setText(String.valueOf(AdbModeConfig.getTcpPort(this)));

        findViewById(R.id.btn_changelog).setOnClickListener(v -> showChangelog());
        findViewById(R.id.btn_check_update).setOnClickListener(v -> runCheck(true));
        // 自动检查：上下午各一次，静默进行（有新版本才提示）。
        if (Updater.shouldAutoCheck(this)) {
            Updater.markAutoChecked(this);
            runCheck(false);
        }
    }

    private void switchMode(String mode) {
        int port = AdbModeConfig.getTcpPort(this);
        if (AdbModeConfig.MODE_TCP.equals(mode)) {
            try {
                port = Integer.parseInt(portInput.getText().toString().trim());
            } catch (Exception e) {
                port = AdbModeConfig.DEFAULT_TCP_PORT;
            }
            if (port < 1 || port > 65535) {
                Toast.makeText(this, "端口无效，请输入 1–65535", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        String previousMode = AdbModeConfig.getMode(this);
        boolean modeChanged = !mode.equals(previousMode);
        AdbModeConfig.setMode(this, mode, port);
        sendBroadcast(new Intent("io.github.linkhalo.SET_MODE")
                .setPackage("android")
                .putExtra("mode", mode)
                .putExtra("port", port));
        String notice = null;
        if (!modeChanged) {
            // 同一模式重复点击：配置未变，不应打断当前连接。
            notice = "已是当前模式，未做改动";
        } else {
            // 跨模式切换必然重启 adbd → 端口会变、当前连接会断。
            // 不再弹 Toast：地址栏已有「正在切换，等待生成…」提示，按钮高亮也会切到目标模式，
            // 反馈已足够，长 Toast 反而遮挡内容。
            // hook 以 system 权限改写 Global 前，界面读到的仍是旧模式；标记过渡态，
            // 期间用目标模式渲染并显示「正在切换」，避免闪现切换前的旧地址。
            switchingMode = mode;
            switchingStartedAt = System.currentTimeMillis();
            oldTlsPortOnSwitch = latestTlsPort(readLog());
            // 立即写死地址栏与胶囊的过渡文案，不依赖 refreshAll 里的竞态判定，
            // 确保点击瞬间用户一定能在地址栏看到「正在切换」。
            addressView.setText("正在切换，等待生成…");
            statusTitle.setText("切换中");
        }
        refreshAll(notice);
        if (!modeChanged) {
            Toast.makeText(this, notice, Toast.LENGTH_SHORT).show();
        }
        portInput.postDelayed(() -> refreshAll(null), 1500);
        // 切模式后端口要等 adbd 重启完才稳定，多刷几次把新地址取回来。
        portInput.postDelayed(() -> refreshAll(null), 4000);
        portInput.postDelayed(() -> refreshAll(null), 8000);
        // 兜底：过渡窗口（3.5s）结束后再刷新一次，确保地址已生成时及时显示。
        portInput.postDelayed(() -> refreshAll(null), 11_000);
    }

    private void refreshAll(String noticeOverride) {
        String globalMode = AdbModeConfig.getMode(this);
        String logNow = readLog();
        int port = AdbModeConfig.getTcpPort(this);
        // 过渡态持续到「新地址真的生成」或超时：TLS 比对端口是否变化（旧端口不算新地址），
        // TCP 用 Global 落库作为信号。避免固定时间到点后仍显示切换前的旧端口。
        boolean switching = false;
        if (switchingMode != null) {
            if (System.currentTimeMillis() - switchingStartedAt >= SWITCH_TIMEOUT_MS) {
                switchingMode = null;
            } else if (AdbModeConfig.MODE_TCP.equals(switchingMode)) {
                // TCP 也要等「地址真的可显示」才结束过渡：仅 Global 落库还不够，
                // 否则会出现「正在切换」消失后短暂闪「等待地址…」再变正确地址。
                boolean addrReady = AdbModeConfig.MODE_TCP.equals(globalMode)
                        && resolveAddress(AdbModeConfig.MODE_TCP, port, logNow) != null;
                switching = !addrReady;
                if (!switching) switchingMode = null;
            } else {
                // TLS 必须同时满足：① hook 已把目标模式落库到 Global（否则 mode 会回退到旧模式，
                // resolveAddress 按旧模式算出旧地址）；② 日志出现不同于切换前的新端口。
                // 只满足②就结束会出现「等待生成消失后仍显示旧端口，几秒后才正确」。
                int cur = latestTlsPort(logNow);
                boolean confirmed = switchingMode.equals(globalMode)
                        && cur > 0 && cur != oldTlsPortOnSwitch;
                switching = !confirmed;
                if (!switching) switchingMode = null;
            }
        }
        String mode = switchingMode != null ? switchingMode : globalMode;
        portInput.setText(String.valueOf(port));
        updateModeButtons(mode);
        modeLabel.setText(AdbModeConfig.MODE_TLS.equals(mode)
                ? "TLS 无线调试（随机端口）"
                : ("TCP 固定端口（" + port + "）"));

        boolean enabled = AdbModeConfig.isEnabled(this);
        syncHero(enabled);

        String log = logNow;
        String preview = recentLogLines(log, LOG_PREVIEW_LINES);
        boolean empty = preview == null || preview.isEmpty();
        if (empty) {
            logEmpty.setVisibility(View.VISIBLE);
            logScroll.setVisibility(View.GONE);
            logView.setText("");
        } else {
            logEmpty.setVisibility(View.GONE);
            logScroll.setVisibility(View.VISIBLE);
            logView.setText(preview);
        }

        String address = enabled && !switching ? resolveAddress(mode, port, log) : null;
        addressView.setText(!enabled ? "已关闭 · 点右上开关开启"
                : (switching ? "正在切换，等待生成…"
                : (address == null ? "等待地址…" : address)));
        boolean ready = enabled && !switching && address != null;
        statusTitle.setText(!enabled ? "无线调试已关闭"
                : (switching ? "切换中" : (ready ? "无线调试已开启" : "等待连接")));
        statusTitle.setBackgroundTintList(ColorStateList.valueOf(
                getColor(ready ? R.color.fl_running_bg : R.color.fl_idle_bg)));
        statusTitle.setTextColor(getColor(ready ? R.color.fl_running_fg : R.color.fl_idle_fg));
        // 顶部胶囊只反映模块在 LSPosed 中是否启用，严格两态；不显示切换/关闭/复制等其他内容。
        // noticeOverride 仅为兼容既有调用点保留，不再影响顶部胶囊。
        boolean moduleLoaded = EventLog.isModuleLoaded(this, log);
        setNotice(moduleLoaded ? "模块已启用" : "模块未启用");
    }

    /** 圆开关外观：开=蓝底白闪电；关=灰底断电图标。 */
    private void syncHero(boolean enabled) {
        heroIcon.setImageResource(enabled ? R.drawable.ic_zap : R.drawable.ic_zap_off);
        heroAction.setBackgroundTintList(ColorStateList.valueOf(
                getColor(enabled ? R.color.fl_accent : R.color.fl_surface_variant)));
        heroIcon.setImageTintList(ColorStateList.valueOf(
                getColor(enabled ? R.color.fl_on_accent : R.color.fl_on_surface_variant)));
    }

    private void updateModeButtons(String mode) {
        boolean tls = AdbModeConfig.MODE_TLS.equals(mode);
        int selected = getColor(R.color.fl_accent);
        int idle = getColor(R.color.fl_surface_variant);
        int selectedText = getColor(R.color.fl_on_accent);
        int idleText = getColor(R.color.fl_on_surface_variant);

        modeTlsBtn.setBackgroundTintList(ColorStateList.valueOf(tls ? selected : idle));
        modeTcpBtn.setBackgroundTintList(ColorStateList.valueOf(tls ? idle : selected));
        // 选中态决定波纹配色：蓝底用白色高亮，浅底用暗色高亮。
        modeTlsBtn.setActivated(tls);
        modeTcpBtn.setActivated(!tls);
        modeTlsText.setTextColor(tls ? selectedText : idleText);
        modeTcpText.setTextColor(tls ? idleText : selectedText);
        modeTlsIcon.setImageTintList(ColorStateList.valueOf(tls ? selectedText : idleText));
        modeTcpIcon.setImageTintList(ColorStateList.valueOf(tls ? idleText : selectedText));
    }

    private void setNotice(String text) {
        noticeChip.setText(text);
    }

    private String readLog() {
        return EventLog.readMerged(this);
    }

    /** 只展示最近几条，倒序（最新在上）。 */
    private static String recentLogLines(String log, int limit) {
        if (log == null || log.isEmpty() || limit <= 0) return "";
        String[] lines = log.split("\n", -1);
        int end = lines.length;
        while (end > 0 && lines[end - 1].trim().isEmpty()) end--;
        if (end == 0) return "";
        int start = Math.max(0, end - limit);
        StringBuilder out = new StringBuilder();
        for (int i = end - 1; i >= start; i--) {
            if (lines[i].trim().isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(lines[i]);
        }
        return out.toString();
    }

    private String resolveAddress(String mode, int port, String log) {
        String ip = findWifiIpv4();
        // TCP：端口是配置里确定的固定值，优先用「当前 IP + 该端口」计算。
        if (AdbModeConfig.MODE_TCP.equals(mode)) {
            if (ip != null) return ip + ":" + port;
            // App 侧 Java 网卡枚举在部分机型（如一加 15）拿不到 IP，此时用缓存地址兜底，
            // 但必须校验缓存端口 == 当前固定端口：否则就是上一次 TLS 的随机端口残留，
            // 切回 TCP 时会闪现旧端口——这正是要修的现象。校验不过就留空。
            String saved = EventLog.readLastAddress(this);
            if (saved != null && saved.endsWith(":" + port)) return saved;
            return null;
        }
        // TLS：端口每次 adbd 重启都会重排，取日志里最新的 [端口] 行。
        int tlsPort = latestTlsPort(log);
        String saved = EventLog.readLastAddress(this);
        // App 侧拿不到 IP 时，用缓存地址里的 IP 段 + 日志里的最新端口重组，
        // 保证端口一定是当前的；绝不能整串返回缓存地址（那会以旧端口闪现）。
        String host = ip;
        if (host == null && saved != null) {
            int colon = saved.lastIndexOf(':');
            if (colon > 0) host = saved.substring(0, colon);
        }
        if (host != null && tlsPort > 0) return host + ":" + tlsPort;
        if (host != null) return host + ":?";
        if (saved != null && !saved.isEmpty()) return saved;
        if (log != null) {
            Matcher m = ADDRESS_IN_LOG.matcher(log);
            String last = null;
            while (m.find()) last = m.group(1);
            if (last != null) return last;
        }
        return null;
    }

    /** 日志里最后一条「[端口] TLS 端口=N」，即当前 adbd 实例的真实 TLS 端口。 */
    private static int latestTlsPort(String log) {
        if (log == null || log.isEmpty()) return -1;
        Matcher m = TLS_PORT_IN_LOG.matcher(log);
        int last = -1;
        while (m.find()) {
            try {
                last = Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) { }
        }
        return last;
    }

    private static String findWifiIpv4() {
        // 优先按 wlan/wifi 网卡名取（与 hook 侧一致）。
        String fallback = null;
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                String name = network.getName();
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                    String host = address.getHostAddress();
                    if (host == null || host.startsWith("172.19.")) continue; // 172.19.* = 虚拟/VPN 段
                    if (name.startsWith("wlan") || name.startsWith("wifi")) return host;
                    // 记下第一个非环回 IPv4 作为兜底：部分 ROM（ColorOS）App 侧网卡名
                    // 不带 wlan/wifi 前缀，若严格按名过滤会拿不到 IP，只能靠缓存地址兜底。
                    if (fallback == null) fallback = host;
                }
            }
        } catch (Throwable ignored) { }
        return fallback;
    }

    // ------------------------------------------------------------------ app 内更新

    /** 更新日志弹窗：与卡片同风格，点外部关闭。 */
    private void showChangelog() {
        Dialog dialog = newPanel("更新日志");
        TextView body = new TextView(this);
        body.setText(CHANGELOG);
        body.setTextSize(13f);
        body.setTextColor(getColor(R.color.fl_on_surface));
        body.setLineSpacing(dp(4), 1f);
        body.setPadding(0, dp(12), 0, 0);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.addView(body);
        panelOf(dialog).addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        showSized(dialog, 0.86f, 0.72f);
    }

    /** 检查更新：后台线程查 GitHub Releases，主线程弹结果。 */
    private void runCheck(final boolean manual) {
        if (checkingUpdate) return;
        checkingUpdate = true;
        final long startedAt = System.currentTimeMillis();
        // 自动检查是静默的，只有手动点按钮才需要「正在检查」的反馈。
        if (manual) setCheckingUi(true);
        updateExec.execute(() -> {
            final Updater.UpdateInfo info = Updater.check(this);
            uiHandler.post(() -> {
                checkingUpdate = false;
                if (isFinishing() || isDestroyed()) return;
                Runnable finish = () -> {
                    if (manual) setCheckingUi(false);
                    if (info != null) {
                        showUpdateDialog(info);
                    } else if (manual) {
                        String err = Updater.getLastError();
                        Toast.makeText(this, err != null ? "检查失败：" + err : "已是最新版本",
                                Toast.LENGTH_SHORT).show();
                    }
                };
                // 网络很快时进度圈会一闪而过，看不出反馈；手动检查至少让状态停留 600ms。
                long wait = manual ? 600L - (System.currentTimeMillis() - startedAt) : 0L;
                if (wait > 0) uiHandler.postDelayed(finish, wait);
                else finish.run();
            });
        });
    }

    /** 手动检查期间：按钮内显示进度圈、文案改为「检查更新中…」，并禁用重复点击。 */
    private void setCheckingUi(boolean checking) {
        View btn = findViewById(R.id.btn_check_update);
        View spinner = findViewById(R.id.btn_check_spinner);
        TextView label = findViewById(R.id.btn_check_label);
        if (btn != null) {
            btn.setEnabled(!checking);
            btn.setAlpha(checking ? 0.8f : 1f);
        }
        if (spinner != null) spinner.setVisibility(checking ? View.VISIBLE : View.GONE);
        if (label != null) label.setText(checking ? "检查更新中…" : "检查更新");
    }

    /** 通用卡片式对话框，返回一个只有标题的 LinearLayout 面板。 */
    private Dialog newPanel(String title) {
        Dialog dialog = new Dialog(this);
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        dialog.getWindow().setDimAmount(0.45f);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.fl_round);
        panel.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.fl_surface)));
        panel.setPadding(dp(20), dp(18), dp(20), dp(18));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(17f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(getColor(R.color.fl_accent));
        panel.addView(t);
        // 用 id 把 panel 挂到 decor：showChangelog 里通过 getDecorView 取回。
        panel.setId(R.id.update_panel_root);
        dialog.setContentView(panel);
        return dialog;
    }

    /** 新建的 panel 取回（setContentView 的视图即 decor 内容根）。 */
    private LinearLayout panelOf(Dialog dialog) {
        return dialog.findViewById(R.id.update_panel_root);
    }

    private void showSized(Dialog dialog, float widthRatio, float heightRatio) {
        dialog.show();
        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        int w = (int) (m.widthPixels * widthRatio);
        int h = heightRatio <= 0 ? -2 : (int) (m.heightPixels * heightRatio);
        dialog.getWindow().setLayout(w, h);
    }

    private TextView dialogLabel(String text, float size, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(getColor(color));
        return t;
    }

    /** 在面板底部加一行按钮（最后一个为主按钮，蓝底）。 */
    private void addButtons(LinearLayout panel, Dialog dialog, String[] texts, Runnable[] actions) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        row.setPadding(0, dp(16), 0, 0);
        for (int i = 0; i < texts.length; i++) {
            boolean primary = i == texts.length - 1;
            TextView b = new TextView(this);
            b.setText(texts[i]);
            b.setTextSize(14f);
            b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            b.setGravity(Gravity.CENTER);
            b.setBackgroundResource(R.drawable.fl_round_sm);
            b.setBackgroundTintList(ColorStateList.valueOf(getColor(primary ? R.color.fl_accent : R.color.fl_surface_variant)));
            b.setTextColor(getColor(primary ? R.color.fl_on_accent : R.color.fl_on_surface));
            b.setPadding(dp(16), dp(10), dp(16), dp(10));
            b.setClickable(true);
            b.setFocusable(true);
            final Runnable action = actions[i];
            b.setOnClickListener(v -> action.run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(40));
            if (i > 0) lp.leftMargin = dp(8);
            row.addView(b, lp);
        }
        panel.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void showUpdateDialog(Updater.UpdateInfo info) {
        // 同版本号但 sha 不同（重新出包）时，标题不能写「新版本」，避免与本地版本号相同让人困惑。
        boolean sameVersion = info.version.equals(currentVersionName());
        Dialog dialog = newPanel(sameVersion
                ? "发现更新包 " + info.version
                : "发现新版本 " + info.version);
        LinearLayout panel = panelOf(dialog);
        String notes = plainNotes(info.body);
        int notesTop;
        if (notes.isEmpty()) {
            notes = "有新版本可用，是否下载并安装？";
            notesTop = dp(12);
        } else {
            // 标题已写明版本号，这里只标注正文性质；跨多个版本更新时正文只含最新一版。
            TextView caption = dialogLabel("本次更新内容", 12f, R.color.fl_on_surface_variant);
            caption.setPadding(0, dp(10), 0, 0);
            panel.addView(caption, new LinearLayout.LayoutParams(-1, -2));
            notesTop = dp(4);
        }
        TextView t = dialogLabel(notes, 13f, R.color.fl_on_surface);
        t.setLineSpacing(dp(4), 1f);
        t.setPadding(0, notesTop, 0, 0);
        // 说明较长时给固定高度并滚动，否则弹窗会被撑出屏幕、底部按钮点不到。
        boolean longNotes = notes.length() > 420 || notes.split("\n").length > 10;
        if (longNotes) {
            ScrollView sv = new ScrollView(this);
            sv.setVerticalScrollBarEnabled(false);
            sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
            sv.addView(t);
            panel.addView(sv, new LinearLayout.LayoutParams(-1, dp(300)));
        } else {
            panel.addView(t, new LinearLayout.LayoutParams(-1, -2));
        }
        addButtons(panel, dialog, new String[]{"取消", "下载"},
                new Runnable[]{dialog::dismiss, () -> { dialog.dismiss(); startDownload(info); }});
        showSized(dialog, 0.86f, 0f);
    }

    private String currentVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private void startDownload(Updater.UpdateInfo info) {
        Dialog dialog = newPanel("下载更新 " + info.version);
        dialog.setCancelable(false);
        LinearLayout panel = panelOf(dialog);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, dp(10));
        barLp.topMargin = dp(16);
        panel.addView(bar, barLp);
        TextView status = dialogLabel("准备下载…", 12f, R.color.fl_on_surface_variant);
        status.setPadding(0, dp(10), 0, 0);
        panel.addView(status, new LinearLayout.LayoutParams(-1, -2));
        TextView cancel = dialogLabel("取消", 14f, R.color.fl_on_surface);
        cancel.setBackgroundResource(R.drawable.fl_round_sm);
        cancel.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.fl_surface_variant)));
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(dp(16), dp(10), dp(16), dp(10));
        cancel.setClickable(true);
        cancel.setOnClickListener(v -> { Updater.cancel(); dialog.dismiss(); });
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(-1, dp(40));
        cancelLp.topMargin = dp(16);
        panel.addView(cancel, cancelLp);
        showSized(dialog, 0.82f, 0f);
        downloadDialog = dialog;

        final File apk = new File(getCacheDir(), "apk/update.apk");
        updateExec.execute(() -> {
            final boolean ok = Updater.download(info, apk, pct -> uiHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                bar.setProgress(pct);
                status.setText("下载中 " + pct + "%");
            }));
            uiHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                dialog.dismiss();
                downloadDialog = null;
                if (ok) showInstallDialog(info);
                else if (!Updater.getCancelled())
                    Toast.makeText(this, "下载失败，请稍后重试", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void showInstallDialog(Updater.UpdateInfo info) {
        Dialog dialog = newPanel("下载完成");
        LinearLayout panel = panelOf(dialog);
        TextView t = dialogLabel(info.version + " 已下载完成，是否立即安装？", 13f, R.color.fl_on_surface);
        t.setPadding(0, dp(12), 0, 0);
        panel.addView(t, new LinearLayout.LayoutParams(-1, -2));
        addButtons(panel, dialog, new String[]{"取消", "安装"},
                new Runnable[]{dialog::dismiss, () -> { dialog.dismiss(); launchInstall(); }});
        showSized(dialog, 0.86f, 0f);
    }

    private void launchInstall() {
        if (!Updater.canInstall(this)) {
            Dialog dialog = newPanel("需要安装权限");
            LinearLayout panel = panelOf(dialog);
            TextView t = dialogLabel("请允许本应用「安装未知应用」，才能安装更新。", 13f, R.color.fl_on_surface);
            t.setPadding(0, dp(12), 0, 0);
            panel.addView(t, new LinearLayout.LayoutParams(-1, -2));
            addButtons(panel, dialog, new String[]{"取消", "去设置"},
                    new Runnable[]{dialog::dismiss, () -> { dialog.dismiss(); Updater.openInstallPermission(this); }});
            showSized(dialog, 0.86f, 0f);
            return;
        }
        Updater.install(this);
    }

    /** 把 GitHub markdown release notes 压成纯文本。 */
    private static String plainNotes(String md) {
        if (md == null) return "";
        // API 给的是 Markdown，atom 降级路径给的是已经转过一遍的纯文本；两条都按同样规则清洗。
        String text = md.replace("\r\n", "\n").replace("\r", "\n")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("<[^>]+>", "")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replaceAll("\\*\\*(.+?)\\*\\*", "$1")
                .replaceAll("`([^`]*)`", "$1")
                .replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1");
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String s = line.replaceFirst("^#{1,6}\\s*", "")
                    .replaceFirst("^[-*]\\s*", "• ")
                    .replaceFirst("^>\\s*", "")
                    .trim();
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        String out = sb.toString();
        // 说明是给人看的详情，不再是 240 字的摘要；超长时截断并给出查看入口提示。
        if (out.length() > 1600) out = out.substring(0, 1600).trim() + "…\n（完整说明见 GitHub Releases）";
        return out;
    }

    private static final String CHANGELOG =
            "2.0.0  项目更名为 LinkHalo：包名改为 io.github.linkhalo，应用名与仓库同步更名。"
                    + "因包名变更，需先卸载旧版「无线ADB」再安装；开关/模式等设置沿用原有 Global 键，状态不丢\n\n"
                    + "1.2.33  检查更新提速：API 被限流时改走 gh-proxy 代理同一 API，说明与校验值一并返回，"
                    + "不再抓 atom、不再探测已废弃的 .sha256；镜像 ghproxy.net→gh.llkk.cc，超时 15s→8s\n\n"
                    + "1.2.32  点「检查更新」后按钮内显示进度圈与「检查更新中…」，检查期间禁点；"
                    + "手动检查至少停留 600ms，避免一闪而过\n\n"
                    + "1.2.31  更新提示里直接显示本次更新内容（跨多个版本时只显示最新一版）；"
                    + "GitHub API 被限流时说明改从 releases.atom 取\n\n"
                    + "1.2.29  更新包安装后自动清理缓存（sha 一致才删，避免误删）\n\n"
                    + "1.2.28  闪电按钮＝无线调试总开关：开着就一直开（重启、断网重连都会自动恢复），"
                    + "关着就一直关（重启也不会自己开）；关闭后模块彻底放手，不再自动接管\n\n"
                    + "1.2.28  应用内更新：同版本号换包（sha 不同）也能检测到更新并安装\n\n"
                    + "1.2.27  最下方新增「更新日志」「检查更新」按钮；检查失败自动走 "
                    + "releases/latest 降级链路，避开 GitHub API 限流\n\n"
                    + "1.2.26  取消切换模式时弹出的 Toast 提示\n\n"
                    + "1.2.25  修复切模式后地址栏短暂显示旧端口；顶部胶囊只显示模块状态\n\n"
                    + "1.2.24  修复部分机型取不到 Wi-Fi IP 导致地址空白\n\n"
                    + "1.2.22  地址栏新增「正在切换，等待生成…」过渡提示\n\n"
                    + "1.2.21  顶部精简：去掉副标题，模块状态胶囊左移\n\n"
                    + "1.2.19  文案中文化：「无线调试已开启/已关闭」「模块已启用」\n\n"
                    + "1.2.18  闪电按钮首次改为总开关，开关状态存 Settings.Global，跨重启保持\n\n"
                    + "1.2.16  桌面图标缩小 20%\n\n"
                    + "1.2.15  桌面图标换为绿底白螺旋\n\n"
                    + "1.2.14  适配 libxposed API 102 / ColorOS 17 / Android 17";

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
