package com.xiaosa.ktv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
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

import org.json.JSONObject;
import org.json.JSONTokener;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 潇洒K歌 · 电视播放端 v1.1
 *
 * 相比 v1.0 的改动（针对电视盒子实测反馈）：
 *  1. 登录不再依赖网页登录页——改成原生登录界面 + 自带遥控器屏幕键盘。
 *     网页登录页在老内核上 max-width/min() 失效，logo 会撑成 1000px 宽，导致
 *     "排版很大/很乱、遥控器选不中输入框"；原生登录彻底绕开这个问题。
 *  2. 自动发现更可靠：显示本机 IP/网段/网关，先扫本机网段，找不到再扩大搜索
 *     常见网段；/api/discover 不存在的老服务器会回退到 /api/rooms、/tv/login.html
 *     识别，不再"只能手动输入 IP"。
 *  3. 检测系统 WebView 版本：页面脚本没跑起来时给出明确提示，而不是让用户
 *     对着一个永远"连接中…"的页面发呆。
 *  4. 地址输入原生支持遥控器数字键盘，不再依赖电视盒子的系统输入法。
 */
public class MainActivity extends Activity implements TvKeyboard.Host {
    private static final String TAG = "XiaosaTV";
    private static final String PREFS = "xiaosa_tv";
    private static final String KEY_SERVER = "server_base";
    private static final String KEY_USER = "last_user";
    private static final int DEFAULT_PORT = 8083;

    private static final int MODE_NONE = 0;
    private static final int MODE_LOGIN = 1;
    private static final int MODE_IP = 2;

    private static final int RETURN_WEB = 0;
    private static final int RETURN_LOGIN = 1;
    private static final int RETURN_ERROR = 2;
    private static final int RETURN_COMPAT = 3;

    private FrameLayout root;
    private WebView webView;
    private View setupView;
    private View loginView;
    private View errorView;
    private View compatView;
    private View loadingView;
    private TextView loadingText;
    private TextView setupStatus;
    private TextView setupNetInfo;
    private TextView errorMessage;
    private TextView loginStatus;
    private TextView loginServerText;
    private TextView connBanner;
    private EditText serverInput;
    private EditText loginUser;
    private EditText loginPass;
    private Button searchBtn;
    private Button connectBtn;
    private Button cancelBtn;
    private Button loginBtn;
    private Button keyboardBtn;

    private TvKeyboard loginKeyboard;
    private TvKeyboard ipKeyboard;
    private Dialog ipDialog;
    private int kbMode = MODE_NONE;
    private EditText kbTarget;

    private String currentBase;
    private boolean canCancelSetup;
    private int returnScreen = RETURN_WEB;
    private boolean webLoaded;
    private volatile boolean scanning;
    private ExecutorService scanPool;
    private boolean isTouch;
    private String webViewVersion = "未知";
    private int webViewMajor = 0;
    private String localIp;
    private int localPrefixLen = 24;
    private String gatewayIp;
    /** 所有本机 IPv4 网卡覆盖到的 /24 前缀（Wi-Fi、有线、热点等）。 */
    private final List<String> localPrefixes = new ArrayList<>();
    private String lastLoadError;
    private int watchdogGeneration = 0;
    private boolean loginBusy;
    private long lastLoginSuccessAt;

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

        // 电视盒子/投影仪一般报 TOUCHSCREEN_NOTOUCH；有些盒子会声明触摸屏特性，
        // 所以以 Configuration 为准，避免把电视误当成手机而依赖系统输入法。
        int touch = getResources().getConfiguration().touchscreen;
        isTouch = (touch == Configuration.TOUCHSCREEN_FINGER || touch == Configuration.TOUCHSCREEN_STYLUS);
        detectWebView();

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF07071A);
        setContentView(root);

        createWebView();
        createLoadingView();
        createConnBanner();

        String saved = prefs().getString(KEY_SERVER, "");
        if (saved == null || saved.trim().isEmpty()) {
            showSetup(false, "首次使用：正在自动搜索同局域网的服务器…");
            startDiscovery();
        } else {
            currentBase = saved;
            checkSessionThenLoad(saved);
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    // ------------------------------------------------------------ 设备/内核信息

    private void detectWebView() {
        try {
            String ua = WebSettings.getDefaultUserAgent(this);
            Matcher m = Pattern.compile("Chrome/([0-9]+)").matcher(ua == null ? "" : ua);
            if (m.find()) {
                webViewMajor = Integer.parseInt(m.group(1));
                webViewVersion = "Chrome/" + m.group(1);
            } else {
                // 老 Android 的 WebView UA 里可能只有 Version/4.0，没有 Chrome/xx。
                webViewVersion = "未知（" + (ua == null ? "" : ua) + "）";
            }
            Log.i(TAG, "WebView=" + webViewVersion + " UA=" + ua);
        } catch (Throwable t) {
            Log.w(TAG, "detectWebView failed", t);
        }
    }

    private boolean webViewTooOld() {
        return webViewMajor > 0 && webViewMajor < 60;
    }

    private String androidInfo() {
        String abi = "";
        try {
            if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0) {
                abi = " · " + Build.SUPPORTED_ABIS[0];
            }
        } catch (Throwable ignored) {}
        return "Android " + Build.VERSION.RELEASE + "（API " + Build.VERSION.SDK_INT + "）" + abi;
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
        s.setDefaultTextEncodingName("UTF-8");
        s.setUserAgentString(s.getUserAgentString() + " XiaosaKTV-TV/1.1");

        CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (url == null || isFinishing()) return;
                if (errorView != null) errorView.setVisibility(View.GONE);
                if (compatView != null) compatView.setVisibility(View.GONE);
                // 服务端会话失效会把 /tv 302 到登录页；APK 不显示网页登录页，
                // 直接切到原生登录（老内核上网页登录页排版必然坏）。
                if (isLoginUrl(url) && currentBase != null &&
                        (setupView == null || setupView.getVisibility() != View.VISIBLE)) {
                    showLogin(null);
                    try { view.stopLoading(); } catch (Exception ignored) {}
                    return;
                }
                if (setupView == null || setupView.getVisibility() != View.VISIBLE) {
                    if (!webLoaded) showLoading("正在连接潇洒K歌…");
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url == null || isFinishing()) return;
                if (isLoginUrl(url)) {
                    showLogin(null);
                    return;
                }
                webLoaded = true;
                hideLoading();
                if ((setupView == null || setupView.getVisibility() != View.VISIBLE) &&
                        (loginView == null || loginView.getVisibility() != View.VISIBLE) &&
                        (errorView == null || errorView.getVisibility() != View.VISIBLE) &&
                        (compatView == null || compatView.getVisibility() != View.VISIBLE)) {
                    view.requestFocus();
                }
                view.evaluateJavascript("try{window.focus();}catch(e){}", null);
                if (isTvPage()) {
                    injectTvCompatCss();
                    startTvWatchdog();
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (failingUrl == null || currentBase == null) return;
                if ((setupView == null || setupView.getVisibility() != View.VISIBLE) &&
                        (loginView == null || loginView.getVisibility() != View.VISIBLE) &&
                        failingUrl.startsWith(currentBase + "/tv")) {
                    lastLoadError = description;
                    showError("无法连接服务器\n" + (description == null ? "" : description) +
                            "\n地址：" + currentBase);
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

    private boolean isLoginUrl(String url) {
        try {
            String path = Uri.parse(url).getPath();
            return path != null && path.startsWith("/tv/login");
        } catch (Exception e) {
            return false;
        }
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
            try {
                startActivity(i);
            } catch (Exception e) {
                // 电视盒子通常没有浏览器，不能让 WebView 自己跳走。
                Toast.makeText(this, "这台设备没有可打开此链接的应用", Toast.LENGTH_SHORT).show();
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    // ------------------------------------------------------------ 会话/加载

    /**
     * 先问服务器"这台电视登录过吗"：登录过就直接进 /tv；
     * 没登录过就显示原生登录页；老服务器没有这个接口（404）就直接进 /tv。
     */
    private void checkSessionThenLoad(final String base) {
        currentBase = base;
        if (setupView != null) setupView.setVisibility(View.GONE);
        if (loginView != null) loginView.setVisibility(View.GONE);
        if (errorView != null) errorView.setVisibility(View.GONE);
        if (compatView != null) compatView.setVisibility(View.GONE);
        showLoading("正在连接潇洒K歌…");
        ui.removeCallbacks(connPoll);
        // CookieManager 在 UI 线程读取，工作线程只发请求，避免个别老 WebView 的线程问题。
        final String cookie = CookieManager.getInstance().getCookie(base);
        new Thread(new Runnable() {
            @Override public void run() {
                final NetUtil.Result r = NetUtil.get(base + "/api/tv-auth/session",
                        cookie, 4500, 4500);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (isFinishing() || !base.equals(currentBase)) return;
                        if (r.error != null) {
                            showError("无法连接服务器\n" + humanError(r.error) +
                                    "\n地址：" + base);
                            return;
                        }
                        if (r.code == 200) {
                            boolean authed = false;
                            boolean hasUsers = true;
                            try {
                                JSONObject o = new JSONObject(r.body);
                                authed = o.optBoolean("authed", false);
                                hasUsers = o.optBoolean("hasUsers", true);
                            } catch (Exception ignored) {}
                            if (authed) loadServer(base, true);
                            else showLogin(hasUsers ? null : "还没有电视端账号，请先在「管理后台 → 用户管理」创建一个账号。");
                            return;
                        }
                        if (r.code == 404) {
                            // 老版本服务端没有登录接口，直接按原来的方式打开。
                            loadServer(base, true);
                            return;
                        }
                        if (r.code == 401 || r.code == 403) {
                            showLogin(null);
                            return;
                        }
                        showError("服务器返回异常（HTTP " + r.code + "）\n地址：" + base);
                    }
                });
            }
        }).start();
    }

    private void loadServer(String base, boolean showLoading) {
        currentBase = base;
        webLoaded = false;
        lastLoadError = null;
        watchdogGeneration++;
        ui.removeCallbacks(connPoll);
        if (connBanner != null) connBanner.setVisibility(View.GONE);
        if (setupView != null) setupView.setVisibility(View.GONE);
        if (loginView != null) loginView.setVisibility(View.GONE);
        if (errorView != null) errorView.setVisibility(View.GONE);
        if (compatView != null) compatView.setVisibility(View.GONE);
        if (webView != null) {
            webView.setVisibility(View.VISIBLE);
            if (showLoading) showLoading("正在连接潇洒K歌…");
            // 老内核（Chrome/52 这类）进兼容版 /tv_old：页面功能与 /tv 相同，
            // 但 JS 已转译到 ES5、CSS Grid 换成了 flex。新内核仍然走完整版 /tv。
            String entry = webViewTooOld() ? "/tv_old" : "/tv";
            Log.i(TAG, "load " + base + entry + " (WebView " + webViewVersion + ")");
            webView.loadUrl(base + entry);
            webView.requestFocus();
        }
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
        inner.addView(pb, new LinearLayout.LayoutParams(dp(48), dp(48)));

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

    // ---------------------------------------------------------------- 错误页

    private void showError(String message) {
        ensureErrorView();
        hideLoading();
        if (setupView != null) setupView.setVisibility(View.GONE);
        if (loginView != null) loginView.setVisibility(View.GONE);
        if (compatView != null) compatView.setVisibility(View.GONE);
        if (webView != null) webView.setVisibility(View.GONE);
        errorMessage.setText(message);
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
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(60), dp(28), dp(60), dp(28));

        TextView title = new TextView(this);
        title.setText("连接失败");
        title.setTextColor(0xFFFF6B9D);
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        errorMessage = new TextView(this);
        errorMessage.setTextColor(0xFFD8D8EE);
        errorMessage.setTextSize(16);
        errorMessage.setGravity(Gravity.CENTER);
        errorMessage.setLineSpacing(dp(3), 1f);
        errorMessage.setPadding(0, dp(14), 0, dp(22));
        box.addView(errorMessage);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button retry = makeButton("重试", true);
        Button settings = makeButton("服务器设置", false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(54), 1);
        lp.rightMargin = dp(10);
        row.addView(retry, lp);
        row.addView(settings, new LinearLayout.LayoutParams(0, dp(54), 1));
        box.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView info = new TextView(this);
        info.setText("设备信息：" + webViewVersion + " · " + androidInfo());
        info.setTextColor(0xFF666688);
        info.setTextSize(12);
        info.setGravity(Gravity.CENTER);
        info.setPadding(0, dp(16), 0, 0);
        box.addView(info);

        retry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                errorView.setVisibility(View.GONE);
                if (currentBase != null) checkSessionThenLoad(currentBase);
                else showSetup(true, "请先设置服务器地址");
            }
        });
        settings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 不先隐藏 errorView：showSetup 需要根据它判断"取消后回到错误页"。
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

    // ---------------------------------------------------- 内核过旧提示页

    /**
     * 网页主脚本在很老的 WebView 上会解析失败（页面用了 const/let、箭头函数、
     * async/await 等），表现就是：页面有壳、右上角永远"连接中…"、遥控器按什么都没反应。
     * 与其让用户对着一个坏页面，不如把原因和可采取的动作直接写出来。
     */
    private void showCompat() {
        ensureCompatView();
        hideLoading();
        if (setupView != null) setupView.setVisibility(View.GONE);
        if (loginView != null) loginView.setVisibility(View.GONE);
        if (errorView != null) errorView.setVisibility(View.GONE);
        if (webView != null) webView.setVisibility(View.GONE);
        compatView.setVisibility(View.VISIBLE);
        compatView.bringToFront();
        hideSystemUi();
    }

    private void ensureCompatView() {
        if (compatView != null) return;

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0xFF07071A);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        frame.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(64), dp(24), dp(64), dp(24));
        scroll.addView(box, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("这台设备的浏览器内核太旧");
        title.setTextColor(0xFFFFC24B);
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        TextView msg = new TextView(this);
        msg.setText("当前系统 WebView：" + webViewVersion + "\n" + androidInfo() +
                "\n\n潇洒K歌电视页需要 Chrome/60 或更高版本。这个内核缺少 async/await、" +
                "对象展开和 CSS Grid，页面脚本和布局都无法运行——不是网络或账号问题。\n" +
                "\n最可靠的办法是把盒子的 WebView 实现换成新版 Chrome：\n" +
                "1. 在电脑上下载 Chrome APK（Android 7 可用新版，注意选对上面的 CPU 架构），" +
                "拷到 U 盘装到盒子；\n" +
                "2. 盒子「设置 → 关于」连点版本号，开启开发者选项；\n" +
                "3. 开发者选项 →「WebView 实现」→ 选 Chrome；\n" +
                "4. 完全退出本应用，再重新打开。\n" +
                "\n也可以先用「用浏览器打开」在已装的新版 Chrome 里看（网页里要再登录一次），" +
                "或者换用参考的原生播放器。");
        msg.setTextColor(0xFFD8D8EE);
        msg.setTextSize(15);
        msg.setLineSpacing(dp(4), 1f);
        msg.setPadding(0, dp(16), 0, dp(20));
        box.addView(msg);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        Button retry = makeButton("重试", true);
        Button browser = makeButton("用浏览器打开", false);
        Button webSettings = makeButton("WebView 设置", false);
        LinearLayout.LayoutParams r1a = new LinearLayout.LayoutParams(0, dp(52), 1);
        r1a.rightMargin = dp(8);
        row1.addView(retry, r1a);
        LinearLayout.LayoutParams r1b = new LinearLayout.LayoutParams(0, dp(52), 1.2f);
        r1b.rightMargin = dp(8);
        row1.addView(browser, r1b);
        row1.addView(webSettings, new LinearLayout.LayoutParams(0, dp(52), 1.1f));
        box.addView(row1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(10), 0, 0);
        Button settings = makeButton("服务器设置", false);
        Button exit = makeButton("退出", false);
        LinearLayout.LayoutParams r2a = new LinearLayout.LayoutParams(0, dp(48), 1);
        r2a.rightMargin = dp(8);
        row2.addView(settings, r2a);
        row2.addView(exit, new LinearLayout.LayoutParams(0, dp(48), 1));
        box.addView(row2, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        retry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                compatView.setVisibility(View.GONE);
                if (currentBase != null) loadServer(currentBase, true);
                else showSetup(true, "请先设置服务器地址");
            }
        });
        browser.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openInBrowser(); }
        });
        webSettings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openWebViewSettings(); }
        });
        settings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showSetup(true, "请检查服务器地址");
            }
        });
        exit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        root.addView(frame, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.setVisibility(View.GONE);
        compatView = frame;
    }

    /** 用系统里已安装的浏览器（优先 Chrome）打开电视端网页。 */
    private void openInBrowser() {
        if (currentBase == null) {
            showSetup(true, "请先设置服务器地址");
            return;
        }
        Uri uri = Uri.parse(currentBase + "/tv");
        Intent chrome = new Intent(Intent.ACTION_VIEW, uri);
        chrome.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        chrome.setPackage("com.android.chrome");
        try {
            startActivity(chrome);
            return;
        } catch (Exception ignored) {}
        Intent any = new Intent(Intent.ACTION_VIEW, uri);
        any.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(Intent.createChooser(any, "选择浏览器打开电视端"));
        } catch (Exception e) {
            Toast.makeText(this, "这台设备没有可用的浏览器，可以先安装新版 Chrome", Toast.LENGTH_LONG).show();
        }
    }

    /** 跳到系统的「WebView 实现」设置，方便把内核切换成 Chrome。 */
    private void openWebViewSettings() {
        try {
            startActivity(new Intent("android.settings.WEBVIEW_SETTINGS"));
            return;
        } catch (Exception ignored) {}
        try {
            startActivity(new Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS"));
        } catch (Exception e) {
            Toast.makeText(this, "这台设备没有开放 WebView 设置，请到「设置 → 开发者选项」里找", Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------- 原生登录页

    private void ensureLoginView() {
        if (loginView != null) return;

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
        outer.setPadding(dp(24), dp(18), dp(24), dp(18));
        scroll.addView(outer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int screenW = getResources().getDisplayMetrics().widthPixels;
        final int cardW = Math.min(screenW - dp(48), dp(780));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(30), dp(22), dp(30), dp(20));
        card.setBackground(roundRect(0xEE10102A, 22, 0x33FFFFFF));
        outer.addView(card, new LinearLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("潇洒K歌");
        title.setTextColor(0xFFF4F4FF);
        title.setTextSize(32);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setShadowLayer(dp(16), 0, 0, 0x99C736F7);
        card.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("电视播放端 · 账号登录");
        subtitle.setTextColor(0xFF8888AA);
        subtitle.setTextSize(14);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(2), 0, dp(14));
        card.addView(subtitle);

        loginServerText = new TextView(this);
        loginServerText.setTextColor(0xFF666688);
        loginServerText.setTextSize(11);
        loginServerText.setGravity(Gravity.CENTER);
        loginServerText.setSingleLine(false);
        loginServerText.setMaxLines(2);
        loginServerText.setEllipsize(TextUtils.TruncateAt.END);
        loginServerText.setPadding(0, 0, 0, dp(8));
        card.addView(loginServerText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView userLabel = new TextView(this);
        userLabel.setText("账号");
        userLabel.setTextColor(0xFFB8B8D8);
        userLabel.setTextSize(14);
        userLabel.setPadding(0, dp(6), 0, dp(6));
        card.addView(userLabel);

        loginUser = makeEditText(false, "电视端账号");
        card.addView(loginUser, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        TextView passLabel = new TextView(this);
        passLabel.setText("密码");
        passLabel.setTextColor(0xFFB8B8D8);
        passLabel.setTextSize(14);
        passLabel.setPadding(0, dp(12), 0, dp(6));
        card.addView(passLabel);

        loginPass = makeEditText(true, "密码");
        card.addView(loginPass, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        loginStatus = new TextView(this);
        loginStatus.setTextColor(0xFFFF6B9D);
        loginStatus.setTextSize(13);
        loginStatus.setGravity(Gravity.CENTER);
        loginStatus.setMinHeight(dp(34));
        loginStatus.setPadding(0, dp(8), 0, dp(4));
        card.addView(loginStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        loginBtn = makeButton("登录", true);
        Button settingsBtn = makeButton("服务器设置", false);
        Button exitBtn = makeButton("退出", false);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(50), 1.3f);
        lp1.rightMargin = dp(8);
        row.addView(loginBtn, lp1);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(50), 1);
        lp2.rightMargin = dp(8);
        row.addView(settingsBtn, lp2);
        row.addView(exitBtn, new LinearLayout.LayoutParams(0, dp(50), 0.8f));
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (!isTouch) {
            keyboardBtn = makeButton("屏幕键盘（遥控器）", false);
            LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
            klp.topMargin = dp(10);
            card.addView(keyboardBtn, klp);
        }

        TextView hint = new TextView(this);
        hint.setText(isTouch
                ? "首次登录需要账号密码；登录成功后会记住，下次自动进入。"
                : "遥控器：方向键选择，OK 确认。\n选中输入框后按 OK，会自动打开下方屏幕键盘。");
        hint.setTextColor(0xFF666688);
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(dp(2), 1f);
        hint.setPadding(0, dp(10), 0, 0);
        card.addView(hint);

        if (!isTouch) {
            loginKeyboard = new TvKeyboard(this, this, false);
            LinearLayout.LayoutParams klp2 = new LinearLayout.LayoutParams(
                    cardW, ViewGroup.LayoutParams.WRAP_CONTENT);
            klp2.topMargin = dp(12);
            outer.addView(loginKeyboard.getView(), klp2);
            loginKeyboard.setVisible(true);
        }

        loginUser.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) setKbTarget(MODE_LOGIN, loginUser);
            }
        });
        loginPass.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) setKbTarget(MODE_LOGIN, loginPass);
            }
        });
        loginUser.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!isTouch) openLoginKeyboard(loginUser);
            }
        });
        loginPass.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!isTouch) openLoginKeyboard(loginPass);
            }
        });
        loginUser.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_NEXT) { loginPass.requestFocus(); return true; }
                return false;
            }
        });
        loginPass.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE ||
                        (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    doLogin();
                    return true;
                }
                return false;
            }
        });

        loginBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doLogin(); }
        });
        settingsBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSetup(true, "修改服务器地址"); }
        });
        exitBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        if (keyboardBtn != null) {
            keyboardBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (loginKeyboard != null) {
                        loginKeyboard.setVisible(true);
                        loginKeyboard.focusFirst();
                    }
                }
            });
        }

        root.addView(frame, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.setVisibility(View.GONE);
        loginView = frame;
    }

    private EditText makeEditText(boolean password, String hint) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setTextSize(19);
        e.setTextColor(0xFFF4F4FF);
        e.setHint(hint);
        e.setHintTextColor(0xFF555577);
        e.setPadding(dp(16), dp(8), dp(16), dp(8));
        // 电视上必须能一眼看出焦点在账号还是密码：普通输入框只有很淡的边框，
        // 加上遥焦点的亮色描边。
        GradientDrawable normal = roundRect(0xFF0B0B1E, 12, 0x33FFFFFF);
        GradientDrawable focused = roundRect(0xFF0B0B1E, 12, 0xFF36D9F7);
        focused.setStroke(dp(2), 0xFF36D9F7);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{}, normal);
        e.setBackground(bg);
        e.setInputType(password
                ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)
                : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        e.setImeOptions(password ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_NEXT);
        // 电视上不弹系统输入法（很多盒子根本没有），完全用我们自带的键盘。
        if (!isTouch && Build.VERSION.SDK_INT >= 21) {
            e.setShowSoftInputOnFocus(false);
        }
        return e;
    }

    private void showLogin(String message) {
        ensureLoginView();
        if (setupView != null && setupView.getVisibility() == View.VISIBLE) return;

        hideLoading();
        loginBusy = false;
        if (loginBtn != null) loginBtn.setEnabled(true);
        if (errorView != null) errorView.setVisibility(View.GONE);
        if (compatView != null) compatView.setVisibility(View.GONE);
        if (webView != null) webView.setVisibility(View.GONE);
        loginView.setVisibility(View.VISIBLE);
        loginView.bringToFront();

        String savedUser = prefs().getString(KEY_USER, "");
        if (loginUser.getText().toString().trim().isEmpty() && savedUser != null) {
            loginUser.setText(savedUser);
        }
        if (message != null) setLoginStatus(message, false);
        else if (System.currentTimeMillis() - lastLoginSuccessAt < 8000) {
            // 刚登录成功又被踢回登录页：说明 Cookie 没被 WebView 保存下来。
            setLoginStatus("登录成功但会话没有保存（Cookie 被拦截），请重试", true);
        } else if (!webLoaded) setLoginStatus("请输入电视端账号密码", false);
        else setLoginStatus("请重新登录电视端账号", false);

        if (!isTouch && loginKeyboard != null) {
            loginKeyboard.setVisible(true);
            loginKeyboard.refresh();
        }
        if (loginServerText != null) {
            loginServerText.setText("服务器：" + (currentBase == null ? "未设置" : currentBase) +
                    "\n系统 WebView " + webViewVersion);
        }
        hideSystemUi();
        loginUser.requestFocus();
        setKbTarget(MODE_LOGIN, loginUser);
    }

    private void hideLogin() {
        if (loginView != null) loginView.setVisibility(View.GONE);
    }

    private void setLoginStatus(String text, boolean error) {
        if (loginStatus == null) return;
        loginStatus.setText(text);
        loginStatus.setTextColor(error ? 0xFFFF6B9D : 0xFF8888AA);
    }

    private void doLogin() {
        if (currentBase == null) { showSetup(true, "请先设置服务器地址"); return; }
        if (loginBusy) return;
        final String user = loginUser.getText().toString().trim();
        final String pass = loginPass.getText().toString();
        if (user.isEmpty()) { setLoginStatus("请输入账号", true); loginUser.requestFocus(); return; }
        if (pass.isEmpty()) { setLoginStatus("请输入密码", true); loginPass.requestFocus(); return; }

        loginBusy = true;
        if (loginBtn != null) loginBtn.setEnabled(false);
        setLoginStatus("正在登录…", false);
        final String base = currentBase;
        new Thread(new Runnable() {
            @Override public void run() {
                String body;
                try {
                    JSONObject o = new JSONObject();
                    o.put("username", user);
                    o.put("password", pass);
                    o.put("remember", true);
                    body = o.toString();
                } catch (Exception e) {
                    body = "{\"username\":\"\",\"password\":\"\"}";
                }
                final NetUtil.Result r = NetUtil.postJson(base + "/api/tv-auth/login",
                        body, null, 5000, 5000);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (isFinishing() || !base.equals(currentBase)) return;
                        loginBusy = false;
                        if (loginBtn != null) loginBtn.setEnabled(true);
                        if (r.error != null) {
                            setLoginStatus("登录失败：" + humanError(r.error), true);
                            return;
                        }
                        if (r.code == 200) {
                            applySetCookies(r);
                            lastLoginSuccessAt = System.currentTimeMillis();
                            prefs().edit().putString(KEY_USER, user).apply();
                            setLoginStatus("登录成功，正在进入…", false);
                            hideLogin();
                            loadServer(base, true);
                            return;
                        }
                        if (r.code == 404) {
                            // 老服务器没有登录接口：当作直接进入。
                            hideLogin();
                            loadServer(base, true);
                            return;
                        }
                        String msg = null;
                        try { msg = new JSONObject(r.body).optString("error", null); } catch (Exception ignored) {}
                        if (msg == null || msg.isEmpty()) msg = "登录失败（HTTP " + r.code + "）";
                        setLoginStatus(msg, true);
                    }
                });
            }
        }).start();
    }

    private void applySetCookies(NetUtil.Result r) {
        if (r.headers == null || currentBase == null) return;
        for (Map.Entry<String, List<String>> en : r.headers.entrySet()) {
            if (en.getKey() == null || !en.getKey().equalsIgnoreCase("Set-Cookie")) continue;
            for (String c : en.getValue()) {
                if (c == null) continue;
                // 保留 Max-Age/Expires（"记住登录"要靠它跨重启），只去掉
                // 个别老 WebView 解析不了的 SameSite 属性。
                String cleaned = c.replaceAll("(?i);\\s*SameSite=[^;]*", "");
                CookieManager.getInstance().setCookie(currentBase, cleaned);
            }
        }
        try { CookieManager.getInstance().flush(); } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------ 设置页

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
        outer.setPadding(dp(72), dp(36), dp(72), dp(36));
        scroll.addView(outer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(40), dp(28), dp(40), dp(26));
        card.setBackground(roundRect(0xEE10102A, 24, 0x33FFFFFF));
        outer.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("潇洒K歌");
        title.setTextColor(0xFFF4F4FF);
        title.setTextSize(42);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setShadowLayer(dp(18), 0, 0, 0x99C736F7);
        card.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("电视播放端 · 遥控器操作");
        subtitle.setTextColor(0xFF8888AA);
        subtitle.setTextSize(15);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(2), 0, dp(14));
        card.addView(subtitle);

        setupStatus = new TextView(this);
        setupStatus.setTextColor(0xFFB8B8D8);
        setupStatus.setTextSize(14);
        setupStatus.setGravity(Gravity.CENTER);
        setupStatus.setLineSpacing(dp(3), 1f);
        setupStatus.setMinHeight(dp(40));
        card.addView(setupStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView label = new TextView(this);
        label.setText("服务器地址");
        label.setTextColor(0xFFB8B8D8);
        label.setTextSize(14);
        label.setPadding(0, dp(12), 0, dp(6));
        card.addView(label);

        serverInput = new EditText(this);
        serverInput.setSingleLine(true);
        serverInput.setHint("http://192.168.1.100:8083");
        serverInput.setHintTextColor(0xFF555577);
        serverInput.setTextColor(0xFFF4F4FF);
        serverInput.setTextSize(17);
        serverInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        serverInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        serverInput.setPadding(dp(16), dp(10), dp(16), dp(10));
        GradientDrawable inputNormal = roundRect(0xFF0B0B1E, 12, 0x33FFFFFF);
        GradientDrawable inputFocused = roundRect(0xFF0B0B1E, 12, 0xFF36D9F7);
        inputFocused.setStroke(dp(2), 0xFF36D9F7);
        StateListDrawable inputBg = new StateListDrawable();
        inputBg.addState(new int[]{android.R.attr.state_focused}, inputFocused);
        inputBg.addState(new int[]{}, inputNormal);
        serverInput.setBackground(inputBg);
        serverInput.setId(View.generateViewId());
        if (!isTouch && Build.VERSION.SDK_INT >= 21) {
            serverInput.setShowSoftInputOnFocus(false);
        }
        card.addView(serverInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        TextView hint = new TextView(this);
        hint.setText(isTouch
                ? "不带端口默认 8083；例如 http://192.168.1.100:8083"
                : "不带端口默认 8083。选中地址框按 OK 可用遥控器数字键盘输入。");
        hint.setTextColor(0xFF666688);
        hint.setTextSize(12);
        hint.setPadding(dp(2), dp(6), 0, dp(8));
        card.addView(hint);

        setupNetInfo = new TextView(this);
        setupNetInfo.setTextColor(0xFF666688);
        setupNetInfo.setTextSize(11);
        setupNetInfo.setGravity(Gravity.CENTER);
        setupNetInfo.setLineSpacing(dp(2), 1f);
        setupNetInfo.setPadding(dp(2), 0, 0, dp(12));
        card.addView(setupNetInfo);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        searchBtn = makeButton("自动搜索", false);
        connectBtn = makeButton("连接", true);
        searchBtn.setId(View.generateViewId());
        connectBtn.setId(View.generateViewId());
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(54), 1);
        lp1.rightMargin = dp(10);
        row.addView(searchBtn, lp1);
        row.addView(connectBtn, new LinearLayout.LayoutParams(0, dp(54), 1));
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(14), 0, 0);
        cancelBtn = makeButton("返回", false);
        Button kbBtn = makeButton("屏幕键盘", false);
        Button exitBtn = makeButton("退出应用", false);
        cancelBtn.setId(View.generateViewId());
        kbBtn.setId(View.generateViewId());
        exitBtn.setId(View.generateViewId());
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(48), 1);
        lp2.rightMargin = dp(8);
        row2.addView(cancelBtn, lp2);
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(0, dp(48), 1);
        lp3.rightMargin = dp(8);
        row2.addView(kbBtn, lp3);
        row2.addView(exitBtn, new LinearLayout.LayoutParams(0, dp(48), 1));
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
        kbBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showIpKeypad(); }
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
        serverInput.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!isTouch) showIpKeypad();
            }
        });

        // 遥控器焦点顺序：地址框 → 自动搜索 → 连接；返回/键盘/退出在下一行。
        serverInput.setNextFocusDownId(searchBtn.getId());
        searchBtn.setNextFocusRightId(connectBtn.getId());
        connectBtn.setNextFocusLeftId(searchBtn.getId());
        searchBtn.setNextFocusUpId(serverInput.getId());
        connectBtn.setNextFocusUpId(serverInput.getId());
        cancelBtn.setNextFocusUpId(connectBtn.getId());
        kbBtn.setNextFocusUpId(connectBtn.getId());

        root.addView(frame, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.setVisibility(View.GONE);
        setupView = frame;
    }

    private void showSetup(boolean canCancel, String status) {
        ensureSetupView();
        canCancelSetup = canCancel;
        // 记住是从哪个界面进来的，取消时回到原处，而不是回到一个空白网页。
        if (loginView != null && loginView.getVisibility() == View.VISIBLE) returnScreen = RETURN_LOGIN;
        else if (errorView != null && errorView.getVisibility() == View.VISIBLE) returnScreen = RETURN_ERROR;
        else if (compatView != null && compatView.getVisibility() == View.VISIBLE) returnScreen = RETURN_COMPAT;
        else returnScreen = RETURN_WEB;
        if (webView != null) webView.setVisibility(View.GONE);
        if (loginView != null) loginView.setVisibility(View.GONE);
        if (errorView != null) errorView.setVisibility(View.GONE);
        if (compatView != null) compatView.setVisibility(View.GONE);
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

        updateNetInfo();
        hideSystemUi();
        if (canCancel) serverInput.requestFocus();
        else if (currentBase != null && !currentBase.isEmpty()) connectBtn.requestFocus();
        else searchBtn.requestFocus();
    }

    private void updateNetInfo() {
        if (setupNetInfo == null) return;
        if (localIp == null) scanLocalNetworks();
        StringBuilder sb = new StringBuilder();
        sb.append("本机 ").append(localIp == null ? "未知" : localIp + "/" + localPrefixLen);
        sb.append("　网关 ").append(gatewayIp == null ? "未知" : gatewayIp);
        sb.append("\n系统 WebView ").append(webViewVersion).append("　").append(androidInfo());
        if (webViewTooOld()) sb.append("\nℹ 内核低于 Chrome/60，将使用兼容版电视页 /tv_old");
        setupNetInfo.setText(sb.toString());
        setupNetInfo.setTextColor(webViewTooOld() ? 0xFFFFC24B : 0xFF666688);
    }

    private void hideSetup() {
        if (setupView != null) setupView.setVisibility(View.GONE);
        int to = returnScreen;
        returnScreen = RETURN_WEB;
        if (to == RETURN_LOGIN && loginView != null) {
            loginView.setVisibility(View.VISIBLE);
            loginView.bringToFront();
            if (!isTouch && loginKeyboard != null) {
                loginKeyboard.setVisible(true);
                loginKeyboard.refresh();
            }
            loginUser.requestFocus();
        } else if (to == RETURN_ERROR && errorView != null) {
            errorView.setVisibility(View.VISIBLE);
            errorView.bringToFront();
        } else if (to == RETURN_COMPAT && compatView != null) {
            compatView.setVisibility(View.VISIBLE);
            compatView.bringToFront();
        } else if (webView != null) {
            webView.setVisibility(View.VISIBLE);
            webView.requestFocus();
        }
        hideSystemUi();
    }

    /** 地址输入用的遥控器数字键盘。 */
    private void showIpKeypad() {
        if (serverInput == null) return;
        if (ipDialog != null && ipDialog.isShowing()) {
            ipKeyboard.focusFirst();
            return;
        }
        kbMode = MODE_IP;
        kbTarget = serverInput;
        ipKeyboard = new TvKeyboard(this, this, true);
        ipKeyboard.refresh();

        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout wrap = new FrameLayout(this);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int panelW = Math.min(screenW - dp(32), dp(420));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(panelW,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        plp.bottomMargin = dp(24);
        wrap.addView(ipKeyboard.getView(), plp);
        d.setContentView(wrap);
        Window win = d.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(0x00000000));
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            win.setGravity(Gravity.BOTTOM);
            win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = win.getAttributes();
            lp.dimAmount = 0.55f;
            win.setAttributes(lp);
        }
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface dialog) {
                ipDialog = null;
                if (serverInput != null) serverInput.requestFocus();
            }
        });
        ipDialog = d;
        d.show();
        ipKeyboard.focusFirst();
    }

    // ------------------------------------------------------------ 连接/发现

    private void connect() {
        String base = normalizeBase(serverInput.getText().toString());
        if (base == null) {
            setupStatus.setText("地址格式不对，请输入 IP 或 http://IP:端口");
            return;
        }
        stopScan();
        currentBase = base;
        prefs().edit().putString(KEY_SERVER, base).apply();
        // checkSessionThenLoad 会统一隐藏设置/登录/错误页并显示"正在连接"，
        // 不要先 hideSetup()，否则会把之前的错误页/登录页闪一下。
        checkSessionThenLoad(base);
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
        scheme = scheme.toLowerCase(Locale.US);
        return scheme + "://" + host + ":" + port;
    }

    private void stopScan() {
        scanning = false;
        if (scanPool != null) {
            try { scanPool.shutdownNow(); } catch (Exception ignored) {}
            scanPool = null;
        }
    }

    private void startDiscovery() {
        if (scanning) return;
        if (searchBtn != null) {
            searchBtn.setEnabled(false);
            searchBtn.setText("搜索中…");
        }

        final List<String> prefixes = new ArrayList<>();
        scanLocalNetworks();
        prefixes.addAll(localPrefixes);
        if (gatewayIp != null) addSubnets(prefixes, gatewayIp, 24);
        if (prefixes.isEmpty()) {
            if (searchBtn != null) { searchBtn.setEnabled(true); searchBtn.setText("自动搜索"); }
            if (setupStatus != null) {
                setupStatus.setText("没有检测到局域网地址。请先确认电视盒子已连上 Wi-Fi/网线，或手动输入服务器 IP。");
            }
            return;
        }

        scanning = true;
        final boolean autoConnect = !canCancelSetup;
        if (setupStatus != null) {
            String self = localIp == null ? "未知" : localIp + "/" + localPrefixLen;
            setupStatus.setText("本机 " + self + "，正在扫描 " + prefixes.get(0) + "x …");
        }

        new Thread(new Runnable() {
            @Override public void run() {
                final AtomicBoolean found = new AtomicBoolean(false);
                final AtomicInteger done = new AtomicInteger(0);

                scanPhase(prefixes, found, done, 0, autoConnect);
                if (!found.get() && scanning) {
                    // 常见"盒子在路由器/中继的下级网段，服务器在上游"的情况：
                    // 本机网段扫不到时，扩大搜索 192.168.0/1、10.0.0 这三个最常见的网段。
                    final List<String> common = new ArrayList<>();
                    for (String p : new String[]{"192.168.0.", "192.168.1.", "10.0.0."}) {
                        if (!prefixes.contains(p)) common.add(p);
                    }
                    if (!common.isEmpty() && scanning) {
                        ui.post(new Runnable() {
                            @Override public void run() {
                                if (scanning && setupStatus != null) {
                                    setupStatus.setText("本机网段没找到，正在扩大搜索（192.168.0/1、10.0.0）…");
                                }
                            }
                        });
                        scanPhase(common, found, done, 0, autoConnect);
                    }
                }

                scanning = false;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (searchBtn != null) {
                            searchBtn.setEnabled(true);
                            searchBtn.setText("自动搜索");
                        }
                        if (!found.get() && setupView != null &&
                                setupView.getVisibility() == View.VISIBLE && setupStatus != null) {
                            String self = localIp == null ? "未知" : localIp + "/" + localPrefixLen;
                            String gw = gatewayIp == null ? "未知" : gatewayIp;
                            setupStatus.setText("未自动找到服务器。\n本机 " + self + " · 网关 " + gw +
                                    "\n请确认电视和服务器在同一个网段，或手动输入 IP:8083。");
                        }
                    }
                });
            }
        }).start();
    }

    /**
     * 扫描一批 /24 网段的 1~254。任务用一个固定线程池并发跑，
     * 扫描进度通过 setupStatus 实时显示，找到后立即停止其余探测。
     */
    private void scanPhase(final List<String> prefixes, final AtomicBoolean found,
                           final AtomicInteger done, final int round, final boolean autoConnect) {
        if (prefixes.isEmpty()) return;
        final int total = prefixes.size() * 254;
        final int startDone = done.get();
        final ExecutorService pool = Executors.newFixedThreadPool(48);
        scanPool = pool;

        List<Callable<Void>> tasks = new ArrayList<>(total);
        for (final String prefix : prefixes) {
            for (int i = 1; i <= 254; i++) {
                final String ip = prefix + i;
                tasks.add(new Callable<Void>() {
                    @Override public Void call() {
                        if (found.get() || !scanning) return null;
                        int port;
                        try {
                            port = probe(ip);
                        } catch (Throwable t) {
                            port = -1;
                        }
                        int d = done.incrementAndGet();
                        if (d % 8 == 0) {
                            final int dd = d - startDone;
                            final String pfx = prefix;
                            ui.post(new Runnable() {
                                @Override public void run() {
                                    if (scanning && setupView != null &&
                                            setupView.getVisibility() == View.VISIBLE && setupStatus != null) {
                                        setupStatus.setText("正在扫描 " + pfx + "x … " + dd + "/" + total);
                                    }
                                }
                            });
                        }
                        if (port > 0 && found.compareAndSet(false, true)) {
                            final String base = "http://" + ip + ":" + port;
                            ui.post(new Runnable() {
                                @Override public void run() {
                                    if (isFinishing() || setupView == null ||
                                            setupView.getVisibility() != View.VISIBLE) return;
                                    serverInput.setText(base);
                                    currentBase = base;
                                    prefs().edit().putString(KEY_SERVER, base).apply();
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
                        return null;
                    }
                });
            }
        }
        try {
            pool.invokeAll(tasks);
        } catch (Exception ignored) {
        } finally {
            try { pool.shutdownNow(); } catch (Exception ignored) {}
            if (scanPool == pool) scanPool = null;
        }
    }

    /**
     * 返回端口号（8083 或 8080），没找到返回 -1。
     * 兼容老服务端：没有 /api/discover 时再试 /api/rooms、/tv/login.html。
     */
    private int probe(String ip) {
        final int[] ports = {DEFAULT_PORT, 8080};
        for (int port : ports) {
            String base = "http://" + ip + ":" + port;
            NetUtil.Result r = NetUtil.get(base + "/api/discover", null, 900, 1000);
            if (r.code == 200 && r.body != null && r.body.contains("xiaosa-ktv")) return port;

            if (r.code == 404 || r.code == 401 || r.code == 403) {
                NetUtil.Result r2 = NetUtil.get(base + "/api/rooms", null, 900, 1000);
                if (r2.code == 200 && r2.body != null && r2.body.contains("\"rooms\"")) return port;

                NetUtil.Result r3 = NetUtil.get(base + "/tv/login.html", null, 900, 1000);
                if (r3.code == 200 && r3.body != null &&
                        (r3.body.contains("潇洒K歌") || r3.body.contains("xiaosa-ktv"))) return port;
            }

            // 只有"端口明确拒绝"才值得换 8080 再试一次；超时/不可达说明主机不在线。
            boolean retryOtherPort = r.refused || r.code == 404 || r.code == 401 || r.code == 403;
            if (!retryOtherPort) return -1;
        }
        return -1;
    }

    private void scanLocalNetworks() {
        localIp = null;
        gatewayIp = null;
        localPrefixes.clear();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia == null) continue;
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address)) continue;
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    String s = a.getHostAddress();
                    if (s == null || s.startsWith("127.")) continue;
                    if (localIp == null) {
                        localIp = s;
                        localPrefixLen = ia.getNetworkPrefixLength();
                    }
                    addSubnets(localPrefixes, s, ia.getNetworkPrefixLength());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "scanLocalNetworks failed", e);
        }
        gatewayIp = findGateway();
    }

    private String findGateway() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            for (Network n : cm.getAllNetworks()) {
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null) continue;
                for (RouteInfo r : lp.getRoutes()) {
                    if (r.isDefaultRoute() && r.getGateway() instanceof Inet4Address) {
                        String g = r.getGateway().getHostAddress();
                        if (g != null && !g.startsWith("0.")) return g;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "findGateway failed", e);
        }
        return null;
    }

    /** 把 ip/prefix 覆盖到的 /24 都加进去（/23、/22 这种网段也能扫到）。 */
    private void addSubnets(List<String> out, String ip, int prefixLen) {
        try {
            long value = ipToLong(ip);
            if (value < 0) return;
            int pl = prefixLen;
            if (pl <= 0 || pl > 32) pl = 24;
            int count = 1;
            if (pl >= 22 && pl < 24) count = 1 << (24 - pl);
            if (pl < 22) pl = 24;
            long mask = (pl == 0) ? 0 : (0xFFFFFFFFL << (32 - pl)) & 0xFFFFFFFFL;
            long net = value & mask;
            for (int i = 0; i < count; i++) {
                String p = longToPrefix(net + (i * 256L));
                if (p != null && !out.contains(p)) out.add(p);
            }
        } catch (Exception e) {
            Log.w(TAG, "addSubnets failed: " + ip + "/" + prefixLen, e);
        }
    }

    private static long ipToLong(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return -1;
        long v = 0;
        for (String p : parts) {
            int n;
            try { n = Integer.parseInt(p); } catch (Exception e) { return -1; }
            if (n < 0 || n > 255) return -1;
            v = (v << 8) | n;
        }
        return v;
    }

    private static String longToPrefix(long net) {
        long n = net & 0xFFFFFF00L;
        if (n < 0 || n > 0xFFFFFFFFL) return null;
        return ((n >> 24) & 0xFF) + "." + ((n >> 16) & 0xFF) + "." + ((n >> 8) & 0xFF) + ".";
    }

    // ------------------------------------------------- 网页健康检查/连接提示

    /**
     * 给老内核补两条 CSS：
     *  - .idle-big img 原本用 width:min(380px,60%)，Chrome 79 以下整条声明被忽略，
     *    空闲态大图会按原图尺寸撑开；
     *  - #ktv-subtitle-layer 原本用 inset:0，老内核不认识，字幕层会变成 0×0。
     * 服务端页面已经改成兼容写法；这里再注入一次，保证还没升级服务端的老部署也正常。
     */
    private void injectTvCompatCss() {
        if (webView == null) return;
        String js = "(function(){try{var id='xiaosa-compat-css';" +
                "if(document.getElementById(id))return;" +
                "var s=document.createElement('style');s.id=id;" +
                "s.textContent='.idle-big img{width:60% !important;max-width:380px !important;height:auto !important}" +
                "#ktv-subtitle-layer{top:0 !important;right:0 !important;bottom:0 !important;left:0 !important}';" +
                "if(document.head)document.head.appendChild(s);}catch(e){}})();";
        webView.evaluateJavascript(js, null);
    }

    private void startTvWatchdog() {
        final int gen = ++watchdogGeneration;
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (gen != watchdogGeneration) return;
                checkTvBoot(gen, 0);
            }
        }, 5000);
    }

    private void checkTvBoot(final int gen, final int attempt) {
        if (gen != watchdogGeneration || webView == null || !isTvPage()) return;
        final String js = "(function(){try{var c=document.getElementById('conn-lbl');" +
                "return JSON.stringify({ws:typeof connectWS,sock:(window._ws?1:0),conn:c?c.textContent:'',href:location.href});" +
                "}catch(e){return '';}})()";
        webView.evaluateJavascript(js, new android.webkit.ValueCallback<String>() {
            @Override public void onReceiveValue(String value) {
                if (gen != watchdogGeneration) return;
                String json = decodeJsValue(value);
                if (json.isEmpty()) {
                    if (attempt < 3) ui.postDelayed(new Runnable() {
                        @Override public void run() { checkTvBoot(gen, attempt + 1); }
                    }, 3000);
                    return;
                }
                boolean wsOk = false;
                boolean sock = false;
                String conn = "";
                try {
                    JSONObject o = new JSONObject(json);
                    wsOk = "function".equals(o.optString("ws"));
                    sock = o.optInt("sock", 0) == 1;
                    conn = o.optString("conn");
                } catch (Exception ignored) {}
                // connectWS 是函数、但 window._ws 一直没建起来：说明脚本解析过了，
                // 却在初始化阶段抛错（或 WebSocket 构造器不可用）。这和"网络连不上"
                // 是两回事，应该提示内核/脚本问题，而不是让用户反复重试网络。
                if (!wsOk || !sock) {
                    if (attempt < 3) {
                        ui.postDelayed(new Runnable() {
                            @Override public void run() { checkTvBoot(gen, attempt + 1); }
                        }, 3000);
                    } else {
                        showCompat();
                    }
                    return;
                }
                if (conn.contains("连接中") || conn.isEmpty()) {
                    if (attempt < 2) {
                        ui.postDelayed(new Runnable() {
                            @Override public void run() { checkTvBoot(gen, attempt + 1); }
                        }, 4000);
                    } else {
                        showConnBanner();
                        ui.removeCallbacks(connPoll);
                        ui.postDelayed(connPoll, 5000);
                    }
                } else {
                    hideConnBanner();
                }
            }
        });
    }

    private final Runnable connPoll = new Runnable() {
        @Override public void run() {
            if (webView == null || !isTvPage()) return;
            webView.evaluateJavascript(
                    "(function(){var e=document.getElementById('conn-lbl');return e?e.textContent:'';})()",
                    new android.webkit.ValueCallback<String>() {
                        @Override public void onReceiveValue(String value) {
                            String t = decodeJsValue(value);
                            if (t.contains("在线")) hideConnBanner();
                        }
                    });
            ui.postDelayed(this, 5000);
        }
    };

    private void createConnBanner() {
        connBanner = new TextView(this);
        connBanner.setText("与服务器连接中断，正在自动重连…（按菜单键打开设置）");
        connBanner.setTextColor(0xFFFFFFFF);
        connBanner.setTextSize(13);
        connBanner.setGravity(Gravity.CENTER);
        connBanner.setPadding(dp(16), dp(8), dp(16), dp(8));
        connBanner.setBackgroundColor(0xE6B00020);
        connBanner.setVisibility(View.GONE);
        connBanner.setClickable(true);
        connBanner.setFocusable(true);
        connBanner.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                hideConnBanner();
                if (currentBase != null) loadServer(currentBase, true);
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP;
        root.addView(connBanner, lp);
    }

    private void showConnBanner() {
        if (connBanner != null) {
            connBanner.setVisibility(View.VISIBLE);
            connBanner.bringToFront();
        }
    }

    private void hideConnBanner() {
        if (connBanner != null) connBanner.setVisibility(View.GONE);
    }

    /** evaluateJavascript 返回的是 JSON 字符串字面量，这里解码成真正的文本。 */
    private static String decodeJsValue(String raw) {
        if (raw == null) return "";
        if ("null".equals(raw) || "undefined".equals(raw)) return "";
        try {
            Object o = new JSONTokener(raw).nextValue();
            return o instanceof String ? (String) o : String.valueOf(o);
        } catch (Exception e) {
            return raw;
        }
    }

    // ------------------------------------------------------------ 返回/退出

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
        if (loginView != null && loginView.getVisibility() == View.VISIBLE) return;
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
        if (ipDialog != null && ipDialog.isShowing()) {
            ipDialog.dismiss();
            return;
        }
        if (customView != null) {
            hideCustomView();
            return;
        }
        if (compatView != null && compatView.getVisibility() == View.VISIBLE) {
            showSetup(true, "请检查服务器地址");
            return;
        }
        if (errorView != null && errorView.getVisibility() == View.VISIBLE) {
            showSetup(true, "请检查服务器地址");
            return;
        }
        if (loginView != null && loginView.getVisibility() == View.VISIBLE) {
            showExitDialog();
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
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            handleMenuKey();
            return true;
        }
        if (!hasWindowFocus()) return super.dispatchKeyEvent(event);
        // 电视上 OK 键落在输入框上时，EditText 自己会吞掉回车；这里显式改成
        // "打开屏幕键盘"，保证遥控器按 OK 一定有用。
        if (event.getAction() == KeyEvent.ACTION_DOWN && !isTouch) {
            int k = event.getKeyCode();
            boolean okKey = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER ||
                    k == KeyEvent.KEYCODE_NUMPAD_ENTER;
            if (okKey) {
                View focus = getCurrentFocus();
                if (loginView != null && loginView.getVisibility() == View.VISIBLE &&
                        (focus == loginUser || focus == loginPass)) {
                    openLoginKeyboard((EditText) focus);
                    return true;
                }
                if (setupView != null && setupView.getVisibility() == View.VISIBLE &&
                        focus == serverInput) {
                    showIpKeypad();
                    return true;
                }
            }
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && isTvPage() &&
                webView != null && webView.getVisibility() == View.VISIBLE &&
                (setupView == null || setupView.getVisibility() != View.VISIBLE) &&
                (loginView == null || loginView.getVisibility() != View.VISIBLE) &&
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

    private void handleMenuKey() {
        if (loginView != null && loginView.getVisibility() == View.VISIBLE) {
            if (loginKeyboard != null) {
                if (loginKeyboard.isVisible()) {
                    loginKeyboard.setVisible(false);
                    if (loginBtn != null) loginBtn.requestFocus();
                } else {
                    loginKeyboard.setVisible(true);
                    loginKeyboard.refresh();
                    loginKeyboard.focusFirst();
                }
            }
            return;
        }
        if (setupView != null && setupView.getVisibility() == View.VISIBLE) {
            if (ipDialog != null && ipDialog.isShowing()) ipDialog.dismiss();
            else showIpKeypad();
            return;
        }
        if ((errorView != null && errorView.getVisibility() == View.VISIBLE) ||
                (compatView != null && compatView.getVisibility() == View.VISIBLE)) {
            showSetup(true, "修改服务器地址");
            return;
        }
        if (webView != null && webView.getVisibility() == View.VISIBLE) {
            showSetup(true, "修改服务器地址");
        }
    }

    private boolean isTvPage() {
        if (webView == null) return false;
        String url = webView.getUrl();
        if (url == null) return false;
        String path = Uri.parse(url).getPath();
        return "/tv".equals(path) || "/tv/".equals(path) || "/tv/index.html".equals(path)
                || "/tv/clean.html".equals(path)
                || "/tv_old".equals(path) || "/tv_old/".equals(path)
                || "/tv_old/index.html".equals(path);
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

    // ---------------------------------------------------------- 屏幕键盘回调

    private void setKbTarget(int mode, EditText target) {
        kbMode = mode;
        kbTarget = target;
        if (loginKeyboard != null) loginKeyboard.refresh();
    }

    private void openLoginKeyboard(EditText target) {
        if (loginKeyboard == null) return;
        setKbTarget(MODE_LOGIN, target);
        loginKeyboard.setVisible(true);
        loginKeyboard.refresh();
        loginKeyboard.focusFirst();
    }

    @Override
    public String kbLabel() {
        if (kbMode == MODE_IP) return "输入服务器地址";
        if (kbTarget == loginPass) return "正在输入：密码";
        return "正在输入：账号";
    }

    @Override
    public String kbValue() {
        if (kbTarget == null) return "";
        String text = kbTarget.getText().toString();
        if (kbTarget == loginPass) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(text.length(), 40); i++) sb.append("●");
            return sb.toString();
        }
        return text;
    }

    @Override
    public void kbChar(String c) {
        if (kbTarget == null) return;
        Editable ed = kbTarget.getText();
        int start = Math.max(0, kbTarget.getSelectionStart());
        int end = Math.max(start, kbTarget.getSelectionEnd());
        if (start > ed.length()) start = ed.length();
        if (end > ed.length()) end = ed.length();
        ed.replace(start, end, c);
        kbTarget.setSelection(Math.min(ed.length(), start + c.length()));
        refreshKb();
    }

    @Override
    public void kbBackspace() {
        if (kbTarget == null) return;
        Editable ed = kbTarget.getText();
        int start = Math.max(0, kbTarget.getSelectionStart());
        int end = Math.max(start, kbTarget.getSelectionEnd());
        if (start != end) {
            ed.delete(Math.min(start, end), Math.max(start, end));
        } else if (start > 0) {
            ed.delete(start - 1, start);
        }
        refreshKb();
    }

    @Override
    public void kbClear() {
        if (kbTarget == null) return;
        kbTarget.setText("");
        refreshKb();
    }

    @Override
    public void kbDone() {
        if (kbMode == MODE_IP) {
            if (ipDialog != null && ipDialog.isShowing()) ipDialog.dismiss();
            connect();
            return;
        }
        if (kbMode == MODE_LOGIN) {
            String u = loginUser.getText().toString().trim();
            String p = loginPass.getText().toString();
            // "完成"当作"下一项"用：先账号、再密码、最后提交，
            // 遥控器只需要反复按 OK，不用在键盘和按钮之间来回找焦点。
            if (kbTarget == loginUser && p.isEmpty()) {
                openLoginKeyboard(loginPass);
                return;
            }
            if (u.isEmpty()) {
                openLoginKeyboard(loginUser);
                return;
            }
            if (p.isEmpty()) {
                openLoginKeyboard(loginPass);
                return;
            }
            if (loginBtn != null) loginBtn.requestFocus();
            doLogin();
            return;
        }
        if (loginBtn != null) loginBtn.requestFocus();
        doLogin();
    }

    @Override
    public void kbSwitch() {
        if (kbMode != MODE_LOGIN || loginKeyboard == null) return;
        if (kbTarget == loginUser) loginPass.requestFocus();
        else loginUser.requestFocus();
        loginKeyboard.setVisible(true);
        loginKeyboard.refresh();
    }

    @Override
    public void kbClose() {
        if (kbMode == MODE_IP) {
            if (ipDialog != null && ipDialog.isShowing()) ipDialog.dismiss();
            return;
        }
        if (loginKeyboard != null) {
            loginKeyboard.setVisible(false);
            if (loginBtn != null) loginBtn.requestFocus();
        }
    }

    private void refreshKb() {
        if (loginKeyboard != null && loginKeyboard.isVisible()) loginKeyboard.refresh();
        if (ipKeyboard != null) ipKeyboard.refresh();
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
        watchdogGeneration++;
        stopScan();
        ui.removeCallbacksAndMessages(null);
        if (ipDialog != null) {
            try { ipDialog.dismiss(); } catch (Exception ignored) {}
            ipDialog = null;
        }
        if (webView != null) {
            try { webView.loadUrl("about:blank"); } catch (Exception ignored) {}
            try { webView.destroy(); } catch (Exception ignored) {}
            webView = null;
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- UI 小工具

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String humanError(String raw) {
        if (raw == null) return "未知错误";
        if (raw.contains("refused")) return "连接被拒绝（端口未开放）";
        if (raw.contains("timeout")) return "连接超时（请检查 IP 和网络）";
        if (raw.contains("unreachable")) return "网络不可达";
        if (raw.contains("unknown host")) return "地址解析失败";
        return raw;
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
        b.setTextSize(16);
        b.setTextColor(primary ? 0xFF07071A : 0xFFF4F4FF);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setPadding(dp(14), 0, dp(14), 0);
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
