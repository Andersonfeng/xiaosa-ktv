package com.xiaosa.ktv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 潇洒K歌 · 电视播放端
 *
 * 一个专门给电视盒子/投影仪用的 WebView 外壳：
 *  - 首次启动自动扫描同网段 8083 端口的 /api/discover，找到 KTV 服务；
 *  - 也可以手动输入 http://IP:8083；
 *  - 加载服务端的 /tv 网页播放器（与浏览器版完全同一套页面）；
 *  - 把网页背景设成深色、全屏沉浸、横屏、保持亮屏；
 *  - 遥控器方向键/OK/返回/菜单键直接交给网页的焦点系统。
 */
public class MainActivity extends Activity {
    private static final String TAG = "XiaosaTV";
    private static final String PREFS = "xiaosa_tv";
    private static final String KEY_SERVER = "server_base";
    private static final int DEFAULT_PORT = 8083;

    private FrameLayout root;
    private WebView webView;
    private View setupView;
    private View errorView;
    private View loadingView;
    private TextView loadingText;
    private TextView setupStatus;
    private TextView errorMessage;
    private EditText serverInput;
    private Button searchBtn;
    private Button connectBtn;
    private Button cancelBtn;

    private String currentBase;
    private boolean canCancelSetup;
    private boolean webLoaded;
    private volatile boolean scanning;
    private ExecutorService scanPool;

    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        hideSystemUi();

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF07071A);
        setContentView(root);

        createWebView();
        createLoadingView();

        String saved = prefs().getString(KEY_SERVER, "");
        if (saved == null || saved.trim().isEmpty()) {
            showSetup(false, "输入电视/服务器地址，或点「自动搜索」");
            startDiscovery();
        } else {
            currentBase = saved;
            loadServer(saved, true);
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- WebView

    private void createWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(0xFF07071A);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " XiaosaKTV-TV/1.0");

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (errorView != null) errorView.setVisibility(View.GONE);
                if (setupView == null || setupView.getVisibility() != View.VISIBLE) {
                    if (!webLoaded) showLoading("正在连接潇洒K歌…");
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                webLoaded = true;
                hideLoading();
                if ((setupView == null || setupView.getVisibility() != View.VISIBLE) &&
                        (errorView == null || errorView.getVisibility() != View.VISIBLE)) {
                    view.requestFocus();
                }
                view.evaluateJavascript("try{window.focus();}catch(e){}", null);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (failingUrl == null || currentBase == null) return;
                // 只处理主页面（/tv...）的失败，图片/接口失败不弹全屏错误。
                if ((setupView == null || setupView.getVisibility() != View.VISIBLE) &&
                        failingUrl.startsWith(currentBase + "/tv")) {
                    showError("无法连接服务器\n" + (description == null ? "" : description));
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress >= 100) hideLoading();
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                webView.setVisibility(View.GONE);
                hideSystemUi();
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                Log.d(TAG, cm.message() + " @" + cm.lineNumber());
                return true;
            }
        });

        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.setVisibility(View.GONE);
    }

    private boolean handleUrl(String url) {
        if (url == null) return false;
        try {
            Uri u = Uri.parse(url);
            String scheme = u.getScheme();
            if (scheme == null) return false;
            if (scheme.equals("http") || scheme.equals("https")) {
                String host = u.getHost();
                String baseHost = currentBase == null ? null : Uri.parse(currentBase).getHost();
                if (host != null && baseHost != null && host.equalsIgnoreCase(baseHost)) return false;
            }
            if (scheme.equals("about") || scheme.equals("data") || scheme.equals("blob")) return false;
            Intent i = new Intent(Intent.ACTION_VIEW, u);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 加载/错误

    private void loadServer(String base, boolean showLoading) {
        currentBase = base;
        webLoaded = false;
        if (setupView != null) setupView.setVisibility(View.GONE);
        if (errorView != null) errorView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        if (showLoading) showLoading("正在连接潇洒K歌…");
        webView.loadUrl(base + "/tv");
        webView.requestFocus();
        hideSystemUi();
    }

    private void createLoadingView() {
        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(0xCC07071A);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setGravity(Gravity.CENTER);
        inner.setPadding(dp(32), dp(32), dp(32), dp(32));

        ProgressBar pb = new ProgressBar(this);
        pb.getIndeterminateDrawable().setColorFilter(0xFFC736F7, PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams pbp = new LinearLayout.LayoutParams(dp(48), dp(48));
        inner.addView(pb, pbp);

        loadingText = new TextView(this);
        loadingText.setText("正在连接潇洒K歌…");
        loadingText.setTextColor(0xFFF4F4FF);
        loadingText.setTextSize(18);
        loadingText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ltp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ltp.topMargin = dp(18);
        inner.addView(loadingText, ltp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        box.addView(inner, lp);
        root.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        box.setVisibility(View.GONE);
        loadingView = box;
    }

    private void showLoading(String text) {
        if (loadingView == null) return;
        if (loadingText != null) loadingText.setText(text);
        loadingView.setVisibility(View.VISIBLE);
        loadingView.bringToFront();
    }

    private void hideLoading() {
        if (loadingView != null) loadingView.setVisibility(View.GONE);
    }

    private void showError(String message) {
        ensureErrorView();
        hideLoading();
        if (setupView != null) setupView.setVisibility(View.GONE);
        if (errorMessage != null) errorMessage.setText(message);
        errorView.setVisibility(View.VISIBLE);
        errorView.bringToFront();
        hideSystemUi();
    }

    private void ensureErrorView() {
        if (errorView != null) return;

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0xF207071A);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(72), dp(48), dp(72), dp(48));

        TextView title = new TextView(this);
        title.setText("连接失败");
        title.setTextColor(0xFFFF6B9D);
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        errorMessage = new TextView(this);
        errorMessage.setTextColor(0xFFD8D8EE);
        errorMessage.setTextSize(17);
        errorMessage.setGravity(Gravity.CENTER);
        errorMessage.setPadding(0, dp(16), 0, dp(28));
        box.addView(errorMessage);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button retry = makeButton("重试", true);
        Button settings = makeButton("服务器设置", false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(56), 1);
        lp.rightMargin = dp(10);
        row.addView(retry, lp);
        row.addView(settings, new LinearLayout.LayoutParams(0, dp(56), 1));
        box.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        retry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                errorView.setVisibility(View.GONE);
                if (currentBase != null) loadServer(currentBase, true);
            }
        });
        settings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                errorView.setVisibility(View.GONE);
                showSetup(true, "请检查服务器地址");
            }
        });

        FrameLayout.LayoutParams lp2 = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.gravity = Gravity.CENTER;
        frame.addView(box, lp2);
        root.addView(frame, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.setVisibility(View.GONE);
        errorView = frame;
    }

    // ---------------------------------------------------------------- 设置页

    private void ensureSetupView() {
        if (setupView != null) return;

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0xFF07071A);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        frame.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER_HORIZONTAL);
        outer.setPadding(dp(90), dp(48), dp(90), dp(48));
        scroll.addView(outer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(44), dp(34), dp(44), dp(30));
        card.setBackground(roundRect(0xEE10102A, 24, 0x33FFFFFF));
        outer.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("潇洒K歌");
        title.setTextColor(0xFFF4F4FF);
        title.setTextSize(46);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setShadowLayer(dp(18), 0, 0, 0x99C736F7);
        card.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("电视播放端 · 遥控器操作");
        subtitle.setTextColor(0xFF8888AA);
        subtitle.setTextSize(16);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(4), 0, dp(20));
        card.addView(subtitle);

        setupStatus = new TextView(this);
        setupStatus.setTextColor(0xFFB8B8D8);
        setupStatus.setTextSize(14);
        setupStatus.setGravity(Gravity.CENTER);
        setupStatus.setLineSpacing(dp(3), 1f);
        setupStatus.setMinHeight(dp(44));
        card.addView(setupStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView label = new TextView(this);
        label.setText("服务器地址");
        label.setTextColor(0xFFB8B8D8);
        label.setTextSize(15);
        label.setPadding(0, dp(16), 0, dp(8));
        card.addView(label);

        serverInput = new EditText(this);
        serverInput.setSingleLine(true);
        serverInput.setHint("http://192.168.1.100:8083");
        serverInput.setHintTextColor(0xFF555577);
        serverInput.setTextColor(0xFFF4F4FF);
        serverInput.setTextSize(17);
        serverInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        serverInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        serverInput.setPadding(dp(18), dp(12), dp(18), dp(12));
        serverInput.setBackground(roundRect(0xFF0B0B1E, 12, 0x33FFFFFF));
        serverInput.setId(View.generateViewId());
        card.addView(serverInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        TextView hint = new TextView(this);
        hint.setText("不带端口默认 8083；例如 http://192.168.1.100:8083");
        hint.setTextColor(0xFF666688);
        hint.setTextSize(12);
        hint.setPadding(dp(2), dp(8), 0, dp(16));
        card.addView(hint);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        searchBtn = makeButton("自动搜索", false);
        connectBtn = makeButton("连接", true);
        searchBtn.setId(View.generateViewId());
        connectBtn.setId(View.generateViewId());
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(56), 1);
        lp1.rightMargin = dp(10);
        row.addView(searchBtn, lp1);
        row.addView(connectBtn, new LinearLayout.LayoutParams(0, dp(56), 1));
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(18), 0, 0);
        cancelBtn = makeButton("返回", false);
        Button exitBtn = makeButton("退出应用", false);
        cancelBtn.setId(View.generateViewId());
        exitBtn.setId(View.generateViewId());
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(52), 1);
        lp2.rightMargin = dp(10);
        row2.addView(cancelBtn, lp2);
        row2.addView(exitBtn, new LinearLayout.LayoutParams(0, dp(52), 1));
        card.addView(row2, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        searchBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startDiscovery(); }
        });
        connectBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { connect(); }
        });
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { hideSetup(); }
        });
        exitBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showExitDialog(); }
        });
        serverInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO ||
                        (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    connect();
                    return true;
                }
                return false;
            }
        });

        // 让遥控器在地址框、两个按钮之间自然移动
        serverInput.setNextFocusDownId(searchBtn.getId());
        searchBtn.setNextFocusRightId(connectBtn.getId());
        connectBtn.setNextFocusLeftId(searchBtn.getId());
        searchBtn.setNextFocusUpId(serverInput.getId());
        connectBtn.setNextFocusUpId(serverInput.getId());
        cancelBtn.setNextFocusUpId(connectBtn.getId());

        root.addView(frame, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.setVisibility(View.GONE);
        setupView = frame;
    }

    private void showSetup(boolean canCancel, String status) {
        ensureSetupView();
        canCancelSetup = canCancel;
        if (webView != null) webView.setVisibility(View.GONE);
        if (errorView != null) errorView.setVisibility(View.GONE);
        hideLoading();
        setupView.setVisibility(View.VISIBLE);
        setupView.bringToFront();

        cancelBtn.setVisibility(canCancel ? View.VISIBLE : View.GONE);
        String saved = prefs().getString(KEY_SERVER, "");
        if (serverInput.getText().toString().trim().isEmpty()) {
            if (currentBase != null && !currentBase.isEmpty()) serverInput.setText(currentBase);
            else if (saved != null && !saved.isEmpty()) serverInput.setText(saved);
        }
        if (status != null) setupStatus.setText(status);
        else if (currentBase != null && !currentBase.isEmpty())
            setupStatus.setText("当前服务器：" + currentBase);
        else setupStatus.setText("输入电视/服务器地址，或点「自动搜索」");

        hideSystemUi();
        if (canCancel) serverInput.requestFocus();
        else if (currentBase != null && !currentBase.isEmpty()) connectBtn.requestFocus();
        else searchBtn.requestFocus();
    }

    private void hideSetup() {
        if (setupView != null) setupView.setVisibility(View.GONE);
        if (errorView != null) errorView.setVisibility(View.GONE);
        if (webView != null) {
            webView.setVisibility(View.VISIBLE);
            webView.requestFocus();
        }
        hideSystemUi();
    }

    // ---------------------------------------------------------------- 连接

    private void connect() {
        String base = normalizeBase(serverInput.getText().toString());
        if (base == null) {
            setupStatus.setText("地址格式不对，请输入 IP 或 http://IP:端口");
            return;
        }
        currentBase = base;
        prefs().edit().putString(KEY_SERVER, base).apply();
        hideSetup();
        loadServer(base, true);
    }

    private String normalizeBase(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (!s.matches("(?i)^https?://.*")) s = "http://" + s;
        Uri u = Uri.parse(s);
        String host = u.getHost();
        if (host == null || host.trim().isEmpty()) return null;
        int port = u.getPort();
        if (port <= 0) port = DEFAULT_PORT;
        String scheme = u.getScheme();
        if (scheme == null) scheme = "http";
        scheme = scheme.toLowerCase();
        return scheme + "://" + host + ":" + port;
    }

    // ---------------------------------------------------------------- 自动发现

    private void startDiscovery() {
        if (scanning) return;
        scanning = true;
        searchBtn.setEnabled(false);
        searchBtn.setText("搜索中…");
        setupStatus.setText("正在搜索同网段 8083 端口，请稍候…");

        final List<String> prefixes = localPrefixes();
        if (prefixes.isEmpty()) {
            scanning = false;
            searchBtn.setEnabled(true);
            searchBtn.setText("自动搜索");
            setupStatus.setText("没有检测到局域网地址，请手动输入服务器 IP。");
            return;
        }

        final AtomicBoolean found = new AtomicBoolean(false);
        final boolean autoConnect = !canCancelSetup;

        new Thread(new Runnable() {
            @Override public void run() {
                List<Callable<Void>> tasks = new ArrayList<>();
                for (final String prefix : prefixes) {
                    for (int i = 1; i <= 254; i++) {
                        final int host = i;
                        tasks.add(new Callable<Void>() {
                            @Override public Void call() {
                                if (found.get()) return null;
                                final String ip = prefix + host;
                                if (probe(ip)) {
                                    if (found.compareAndSet(false, true)) {
                                        final String url = "http://" + ip + ":" + DEFAULT_PORT;
                                        ui.post(new Runnable() {
                                            @Override public void run() {
                                                if (isFinishing() || setupView == null ||
                                                        setupView.getVisibility() != View.VISIBLE) return;
                                                serverInput.setText(url);
                                                setupStatus.setText("已找到服务器 " + ip + "，正在连接…");
                                                if (autoConnect) {
                                                    ui.postDelayed(new Runnable() {
                                                        @Override public void run() {
                                                            if (!isFinishing() && setupView != null &&
                                                                    setupView.getVisibility() == View.VISIBLE) {
                                                                connect();
                                                            }
                                                        }
                                                    }, 700);
                                                }
                                            }
                                        });
                                    }
                                }
                                return null;
                            }
                        });
                    }
                }

                ExecutorService pool = Executors.newFixedThreadPool(32);
                scanPool = pool;
                try {
                    pool.invokeAll(tasks);
                } catch (Exception ignored) {
                }
                pool.shutdownNow();
                ui.post(new Runnable() {
                    @Override public void run() {
                        scanning = false;
                        if (searchBtn != null) {
                            searchBtn.setEnabled(true);
                            searchBtn.setText("自动搜索");
                        }
                        if (!found.get() && setupStatus != null &&
                                setupView != null && setupView.getVisibility() == View.VISIBLE) {
                            setupStatus.setText("未自动找到服务器，请手动输入 IP:8083。");
                        }
                    }
                });
            }
        }).start();
    }

    private List<String> localPrefixes() {
        Set<String> out = new LinkedHashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia == null) continue;
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String s = a.getHostAddress();
                        int idx = s == null ? -1 : s.lastIndexOf('.');
                        if (idx > 0) out.add(s.substring(0, idx + 1));
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "localPrefixes failed", e);
        }
        return new ArrayList<>(out);
    }

    private boolean probe(String ip) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL("http://" + ip + ":" + DEFAULT_PORT + "/api/discover");
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(450);
            conn.setReadTimeout(700);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Connection", "close");
            conn.setRequestProperty("User-Agent", "XiaosaKTV-TV/1.0");
            int code = conn.getResponseCode();
            if (code != 200) return false;
            InputStream is = conn.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null && sb.length() < 8192) sb.append(line);
            br.close();
            String body = sb.toString();
            return body.contains("xiaosa-ktv") && body.contains("\"app\"");
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    // ---------------------------------------------------------------- 返回/退出

    private void showBackDialog() {
        new AlertDialog.Builder(this)
                .setTitle("潇洒K歌")
                .setMessage("要继续观看，还是修改服务器地址？")
                .setPositiveButton("继续观看", null)
                .setNeutralButton("服务器设置", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        showSetup(true, "修改服务器地址");
                    }
                })
                .setNegativeButton("退出应用", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface dialog) {
                        focusWebAfterDialog();
                    }
                })
                .show();
    }

    private void focusWebAfterDialog() {
        if (webView == null) return;
        if (setupView != null && setupView.getVisibility() == View.VISIBLE) return;
        if (errorView != null && errorView.getVisibility() == View.VISIBLE) return;
        webView.requestFocus();
    }

    private void showExitDialog() {
        new AlertDialog.Builder(this)
                .setTitle("退出潇洒K歌")
                .setMessage("确定退出电视播放端吗？")
                .setPositiveButton("退出", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .setNegativeButton("取消", null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface dialog) {
                        focusWebAfterDialog();
                    }
                })
                .show();
    }

    private void hideCustomView() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
        if (webView != null) {
            webView.setVisibility(View.VISIBLE);
            webView.requestFocus();
        }
        hideSystemUi();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
            return;
        }
        if (errorView != null && errorView.getVisibility() == View.VISIBLE) {
            showSetup(true, "请检查服务器地址");
            return;
        }
        if (setupView != null && setupView.getVisibility() == View.VISIBLE) {
            if (canCancelSetup && currentBase != null && !currentBase.isEmpty()) hideSetup();
            else showExitDialog();
            return;
        }
        // 先让网页关闭自己的弹层（Escape），再弹原生返回菜单。
        if (webView != null) {
            webView.evaluateJavascript(
                    "(function(){try{var e=new KeyboardEvent('keydown',{key:'Escape',code:'Escape',keyCode:27,which:27,bubbles:true,cancelable:true});document.dispatchEvent(e);}catch(err){}})();",
                    null);
        }
        showBackDialog();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!hasWindowFocus()) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_DOWN && isTvPage() &&
                webView != null && webView.getVisibility() == View.VISIBLE &&
                (setupView == null || setupView.getVisibility() != View.VISIBLE) &&
                (errorView == null || errorView.getVisibility() != View.VISIBLE)) {
            int k = event.getKeyCode();
            String key = null, code = null;
            int kc = 0;
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_UP: key = "ArrowUp"; code = "ArrowUp"; kc = 38; break;
                case KeyEvent.KEYCODE_DPAD_DOWN: key = "ArrowDown"; code = "ArrowDown"; kc = 40; break;
                case KeyEvent.KEYCODE_DPAD_LEFT: key = "ArrowLeft"; code = "ArrowLeft"; kc = 37; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: key = "ArrowRight"; code = "ArrowRight"; kc = 39; break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER: key = "Enter"; code = "Enter"; kc = 13; break;
                case KeyEvent.KEYCODE_SPACE:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: key = " "; code = "Space"; kc = 32; break;
                default: break;
            }
            if (key != null) {
                injectWebKey(key, code, kc);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean isTvPage() {
        if (webView == null) return false;
        String url = webView.getUrl();
        if (url == null) return false;
        String path = Uri.parse(url).getPath();
        return "/tv".equals(path) || "/tv/".equals(path) || "/tv/index.html".equals(path);
    }

    private void injectWebKey(String key, String code, int keyCode) {
        // 老 WebView 可能没有 KeyboardEvent 构造器，退回到 createEvent。
        String js = "(function(){try{var e=null;try{e=new KeyboardEvent('keydown',{key:'" + key +
                "',code:'" + code + "',keyCode:" + keyCode + ",which:" + keyCode +
                ",bubbles:true,cancelable:true});}catch(x){e=document.createEvent('Event');" +
                "e.initEvent('keydown',true,true);e.key='" + key + "';e.code='" + code +
                "';e.keyCode=" + keyCode + ";e.which=" + keyCode + ";}" +
                "document.dispatchEvent(e);}catch(err){}})();";
        webView.evaluateJavascript(js, null);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (setupView == null || setupView.getVisibility() != View.VISIBLE) {
                showSetup(true, "修改服务器地址");
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------------------------------------------------------- 系统

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
        try { CookieManager.getInstance().flush(); } catch (Exception ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        hideSystemUi();
    }

    @Override
    protected void onDestroy() {
        if (scanPool != null) scanPool.shutdownNow();
        if (webView != null) {
            try { webView.loadUrl("about:blank"); } catch (Exception ignored) {}
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- UI 小工具

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        return roundRect(color, radiusDp, 0);
    }

    private GradientDrawable roundRect(int color, int radiusDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) d.setStroke(dp(1), strokeColor);
        return d;
    }

    private Button makeButton(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setTextColor(primary ? 0xFF07071A : 0xFFF4F4FF);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setPadding(dp(20), 0, dp(20), 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setStateListAnimator(null);

        GradientDrawable normal = roundRect(primary ? 0xFFC736F7 : 0xFF16162E, 14, 0x33FFFFFF);
        GradientDrawable focused = roundRect(primary ? 0xFF36D9F7 : 0xFF2C2450, 14, 0xFFFFFFFF);
        focused.setStroke(dp(3), 0xFFFFFFFF);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, focused);
        sl.addState(new int[]{android.R.attr.state_pressed}, focused);
        sl.addState(new int[]{}, normal);
        b.setBackground(sl);
        return b;
    }
}
