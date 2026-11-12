package com.xiaosa.ktv;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

/**
 * 极简 HTTP 工具：不引第三方库，直接把 APK 控制在几百 KB。
 *
 * 老安卓（API 21+）上 HttpURLConnection 足够用，并且天然支持明文 HTTP、
 * Range、Cookie 头，适合局域网这种"没有 TLS、没有外网"的场景。
 */
final class NetUtil {
    private NetUtil() {}

    static final class Result {
        int code = -1;
        String body = "";
        String error;
        Map<String, List<String>> headers;
        /** 连接被拒绝（端口没开）——用来快速判断要不要再试第二个端口。 */
        boolean refused;
        /** 超时 / 不可达——说明主机基本不在线，不用再试别的端口。 */
        boolean timeout;

        boolean isOk() { return code >= 200 && code < 300; }
    }

    static Result get(String url, String cookie, int connectMs, int readMs) {
        return request("GET", url, null, null, cookie, connectMs, readMs);
    }

    static Result postJson(String url, String json, String cookie, int connectMs, int readMs) {
        return request("POST", url, json, "application/json; charset=utf-8", cookie, connectMs, readMs);
    }

    private static Result request(String method, String url, String body, String contentType,
                                  String cookie, int connectMs, int readMs) {
        Result r = new Result();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setRequestMethod(method);
            // 发现/登录都自己处理跳转，避免 HttpURLConnection 自动跟随到登录页后
            // 拿到的 200 被误判成"服务可用"。
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Connection", "close");
            conn.setRequestProperty("User-Agent", "XiaosaKTV-TV/1.1");
            conn.setRequestProperty("Accept", "application/json,text/html,*/*");
            if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
            if (contentType != null) conn.setRequestProperty("Content-Type", contentType);
            if (body != null) {
                byte[] bytes = body.getBytes("UTF-8");
                conn.setDoOutput(true);
                // 不用 setFixedLengthStreamingMode：JDK/部分 Android 实现在 401 响应下
                // 会因为"流式 POST 无法重试认证"抛 HttpRetryException，导致读不到
                // 服务端返回的 {"error":"账号或密码错误"}。请求体只有几百字节，
                // 让 HttpURLConnection 自己缓冲并设置 Content-Length 更稳。
                OutputStream os = conn.getOutputStream();
                os.write(bytes);
                os.close();
            }
            r.code = conn.getResponseCode();
            r.headers = conn.getHeaderFields();
            InputStream is = null;
            if (r.code >= 400) {
                is = conn.getErrorStream();
                if (is == null) {
                    try { is = conn.getInputStream(); } catch (Exception ignored) {}
                }
            } else {
                is = conn.getInputStream();
            }
            if (is != null) r.body = readAll(is);
        } catch (SocketTimeoutException e) {
            r.timeout = true;
            r.error = "timeout";
        } catch (ConnectException e) {
            r.refused = true;
            r.error = "refused";
        } catch (NoRouteToHostException e) {
            r.timeout = true;
            r.error = "unreachable";
        } catch (UnknownHostException e) {
            r.error = "unknown host";
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
        return r;
    }

    private static String readAll(InputStream is) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            int total = 0;
            // 发现接口/登录接口都是小 JSON，登录页也只有十几 KB；
            // 设个上限避免某个异常服务器把内存打爆。
            while ((n = is.read(buf)) > 0 && total < 256 * 1024) {
                out.write(buf, 0, n);
                total += n;
            }
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        } finally {
            try { is.close(); } catch (Exception ignored) {}
        }
    }
}
