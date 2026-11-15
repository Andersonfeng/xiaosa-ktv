package com.xiaosa.ktv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;

/**
 * 电视端的"手机输入"小服务：在电视上开一个极简 HTTP 服务，手机扫码打开页面，
 * 输入服务器地址后提交，电视立刻拿到并填进设置页。
 *
 * 为什么自己写：只是为了收一个字符串，引 NanoHTTPD 之类要几百 KB，不值当。
 * 这个实现只支持 GET / 和 POST /set，请求体最大 4KB，连接读超时 8 秒，
 * 不处理 keep-alive、不支持 HTTPS——局域网内一次性使用足够。
 *
 * 生命周期：点「手机输入」时 start()，收到地址或关闭弹窗时 stop()。
 * 线程模型：accept 循环跑在守护线程里，每个连接在同一个线程里同步处理
 * （请求都很小，串行处理最简单，也不会出现并发写状态的问题）。
 */
final class PhoneInputServer {

    interface Listener {
        /** 收到手机提交的地址，回调发生在网络线程，调用方要自己切回 UI 线程。 */
        void onAddress(String address);
    }

    private static final int FIRST_PORT = 8098;
    private static final int LAST_PORT = 8108;

    private final Listener listener;
    private ServerSocket serverSocket;
    private Thread thread;
    private volatile boolean running;
    private int port;

    PhoneInputServer(Listener listener) {
        this.listener = listener;
    }

    /**
     * 启动服务。
     *
     * @return 手机要打开的地址，例如 http://192.168.1.23:8098；启动失败返回 null
     */
    String start(String localIp) {
        if (running) return url(localIp);
        if (localIp == null || localIp.isEmpty()) return null;

        for (int p = FIRST_PORT; p <= LAST_PORT; p++) {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(p));
                port = p;
                break;
            } catch (IOException e) {
                serverSocket = null;
            }
        }
        if (serverSocket == null) return null;

        running = true;
        thread = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "phone-input-server");
        thread.setDaemon(true);
        thread.start();
        return url(localIp);
    }

    void stop() {
        running = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (Exception ignored) {}
            serverSocket = null;
        }
        thread = null;
    }

    private String url(String ip) {
        return "http://" + ip + ":" + port;
    }

    private void acceptLoop() {
        while (running) {
            Socket socket = null;
            try {
                socket = serverSocket.accept();
                socket.setSoTimeout(8000);
                handle(socket);
            } catch (Exception e) {
                // 关闭 socket 时 accept 会抛异常，属于正常退出；其它异常只影响这一次连接
            } finally {
                if (socket != null) {
                    try { socket.close(); } catch (Exception ignored) {}
                }
            }
        }
    }

    private void handle(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream headBuf = new ByteArrayOutputStream();
        // 读到空行为止（请求头以 \r\n\r\n 结束）。直接判断"当前已读内容是否以空行结尾"
        // 比维护四个字节的滑窗状态可靠得多——最早的实现把结束判断写反了，结果
        // 服务端一直等不到结尾、客户端读超时。
        // 上限 8KB，防畸形请求把内存打爆。
        while (headBuf.size() < 8192) {
            int cur = in.read();
            if (cur == -1) return;
            headBuf.write(cur);
            if (headBuf.size() >= 4 && headBuf.toString("ISO-8859-1").endsWith("\r\n\r\n")) break;
        }
        String head = headBuf.toString("ISO-8859-1");
        String[] lines = head.split("\r\n");
        if (lines.length == 0) return;

        String[] requestLine = lines[0].split(" ");
        if (requestLine.length < 2) return;
        String method = requestLine[0];
        String path = requestLine[1];

        int contentLength = 0;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) continue;
            if (lines[i].substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try { contentLength = Integer.parseInt(lines[i].substring(colon + 1).trim()); }
                catch (NumberFormatException ignored) {}
            }
        }
        if (contentLength > 4096) contentLength = 4096;

        String body = "";
        if (contentLength > 0) {
            byte[] buf = new byte[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = in.read(buf, read, contentLength - read);
                if (n <= 0) break;
                read += n;
            }
            body = new String(buf, 0, read, "UTF-8");
        }

        OutputStream out = socket.getOutputStream();
        if (path.startsWith("/favicon.ico")) {
            writeResponse(out, 204, "text/plain; charset=utf-8", "");
            return;
        }
        if ("POST".equalsIgnoreCase(method) && path.startsWith("/set")) {
            String addr = formValue(body, "addr");
            if (addr == null || addr.trim().isEmpty()) {
                writeResponse(out, 400, "text/html; charset=utf-8", donePage("地址是空的，请回到手机上重新输入。", true));
                return;
            }
            String clean = addr.trim();
            if (clean.length() > 200) clean = clean.substring(0, 200);
            if (listener != null) listener.onAddress(clean);
            writeResponse(out, 200, "text/html; charset=utf-8", donePage("已发送到电视，可以放下手机了。", false));
            return;
        }
        if ("GET".equalsIgnoreCase(method) && (path.equals("/") || path.startsWith("/?"))) {
            writeResponse(out, 200, "text/html; charset=utf-8", formPage());
            return;
        }
        writeResponse(out, 404, "text/plain; charset=utf-8", "not found");
    }

    private static void writeResponse(OutputStream out, int code, String contentType, String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes("UTF-8");
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : code == 400 ? " Bad Request" : code == 204 ? " No Content" : " Not Found") + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("ISO-8859-1"));
        if (bytes.length > 0) out.write(bytes);
        out.flush();
    }

    private static String formValue(String body, String key) {
        if (body == null || body.isEmpty()) return null;
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String k = pair.substring(0, eq);
            if (!key.equals(k)) continue;
            try {
                return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
            } catch (Exception e) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    private static String formPage() {
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>潇洒K歌 · 输入服务器地址</title><style>"
                + "body{font-family:-apple-system,Helvetica,Arial,sans-serif;background:#0b0b1e;color:#f4f4ff;"
                + "margin:0;padding:28px 20px;}"
                + "h1{font-size:20px;margin:0 0 6px}p{color:#9a9ac0;font-size:14px;line-height:1.6;margin:0 0 18px}"
                + "input{width:100%;box-sizing:border-box;font-size:18px;padding:14px;border-radius:12px;"
                + "border:1px solid #3a3a5c;background:#16162e;color:#fff;outline:none}"
                + "button{width:100%;margin-top:16px;padding:15px;font-size:17px;font-weight:600;border:0;"
                + "border-radius:12px;background:linear-gradient(135deg,#c736f7,#36d9f7);color:#fff}"
                + ".hint{margin-top:16px;font-size:13px;color:#6a6a90}"
                + "</style></head><body>"
                + "<h1>潇洒K歌</h1><p>请输入电视要连接的服务器地址，提交后电视会自动填好。</p>"
                + "<form method=\"post\" action=\"/set\">"
                + "<input name=\"addr\" placeholder=\"http://192.168.1.100:8083\" autofocus "
                + "autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\" inputmode=\"url\">"
                + "<button type=\"submit\">发送到电视</button></form>"
                + "<p class=\"hint\">可以填 IP 或域名；不带端口默认 8083。</p>"
                + "</body></html>";
    }

    private static String donePage(String message, boolean error) {
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>潇洒K歌</title><style>body{font-family:-apple-system,Helvetica,Arial,sans-serif;"
                + "background:#0b0b1e;color:" + (error ? "#ff6b8a" : "#36d9f7") + ";margin:0;padding:48px 24px;"
                + "text-align:center;font-size:18px;line-height:1.7}</style></head><body>"
                + message + "</body></html>";
    }
}
