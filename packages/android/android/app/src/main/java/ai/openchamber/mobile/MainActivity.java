package ai.openchamber.mobile;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PREFS_NAME = "openchamber_mobile";
    private static final String PREF_SERVERS = "servers";
    private static final String PREF_CURRENT_ID = "current_server_id";
    private static final String PREF_WEB_PREFIX = "web:";
    private static final String LOCALE_STORAGE_KEY = "openchamber.i18n.v1";
    private static final int REQUEST_TIMEOUT_MS = 10000;

    private WebView webView;
    private SharedPreferences prefs;
    private ExecutorService workers;
    private LocalServer localServer;
    private OnBackInvokedCallback backInvokedCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        workers = Executors.newCachedThreadPool();
        configureSystemBars();
        createWebView();
        configureCookies();
        configureWebView();
        configureSystemBackHandler();
        try {
            localServer = new LocalServer();
            localServer.start();
            webView.loadUrl(localServer.url("/mobile.html"));
        } catch (IOException error) {
            Toast.makeText(this, "Failed to start OpenChamber Android", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backInvokedCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backInvokedCallback);
            backInvokedCallback = null;
        }
        if (localServer != null) localServer.stop();
        if (workers != null) workers.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        handleSystemBack();
    }

    private void configureSystemBackHandler() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        backInvokedCallback = this::handleSystemBack;
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                backInvokedCallback
        );
    }

    private void handleSystemBack() {
        if (webView == null) {
            moveTaskToBack(true);
            return;
        }

        String script = "(function(){try{"
                + "var event;"
                + "if(typeof CustomEvent==='function'){"
                + "event=new CustomEvent('openchamber:android-back',{cancelable:true});"
                + "}else{"
                + "event=document.createEvent('Event');"
                + "event.initEvent('openchamber:android-back',false,true);"
                + "}"
                + "return window.dispatchEvent(event)===false||event.defaultPrevented===true;"
                + "}catch(e){return false;}})();";
        webView.evaluateJavascript(script, handled -> {
            if (handled != null && "true".equals(handled.trim())) return;
            moveTaskToBack(true);
        });
    }

    private void configureSystemBars() {
        applySystemBars(Color.rgb(250, 248, 241), true);
    }

    private void applySystemBars(int color, boolean darkIcons) {
        Window window = getWindow();
        window.setStatusBarColor(color);
        window.setNavigationBarColor(color);
        int flags = 0;
        if (darkIcons && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (darkIcons) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private int parseCssColor(String value, int fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return fallback;
        try {
            if (trimmed.startsWith("#")) return Color.parseColor(trimmed);
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.startsWith("rgb(") || lower.startsWith("rgba(")) {
                int start = trimmed.indexOf('(');
                int end = trimmed.lastIndexOf(')');
                if (start >= 0 && end > start) {
                    String[] parts = trimmed.substring(start + 1, end).split(",");
                    if (parts.length >= 3) {
                        return Color.rgb(
                                parseCssColorChannel(parts[0]),
                                parseCssColorChannel(parts[1]),
                                parseCssColorChannel(parts[2])
                        );
                    }
                }
            }
            return Color.parseColor(trimmed);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int parseCssColorChannel(String value) {
        String trimmed = value.trim();
        try {
            if (trimmed.endsWith("%")) {
                float percent = Float.parseFloat(trimmed.substring(0, trimmed.length() - 1).trim());
                return Math.max(0, Math.min(255, Math.round(percent * 2.55f)));
            }
            return Math.max(0, Math.min(255, Math.round(Float.parseFloat(trimmed))));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private boolean shouldUseDarkSystemBarIcons(int color) {
        double red = Color.red(color) / 255.0;
        double green = Color.green(color) / 255.0;
        double blue = Color.blue(color) / 255.0;
        double luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue;
        return luminance > 0.55;
    }

    private void createWebView() {
        FrameLayout root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        setContentView(root);
    }

    private void configureCookies() {
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webView.addJavascriptInterface(new AndroidThemeBridge(), "OpenChamberAndroid");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectSystemBarThemeBridge(view);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request == null ? null : request.getUrl();
                if (uri == null || localServer == null) return false;
                String localOrigin = localServer.origin();
                String raw = uri.toString();
                if (raw.startsWith(localOrigin)) return false;
                if (raw.startsWith("http://") || raw.startsWith("https://")) {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    return true;
                }
                return false;
            }
        });
    }

    private void injectSystemBarThemeBridge(WebView view) {
        String script = "(function(){"
                + "if(window.__openchamberAndroidSystemBarsInstalled){window.__openchamberAndroidNotifySystemBars&&window.__openchamberAndroidNotifySystemBars();return;}"
                + "window.__openchamberAndroidSystemBarsInstalled=true;"
                + "var pending=0;"
                + "function normalizeColor(value){"
                + "var host=document.body||document.documentElement;"
                + "var probe=document.createElement('span');"
                + "probe.style.color=value||'#faf8f1';"
                + "probe.style.display='none';"
                + "host.appendChild(probe);"
                + "var color=getComputedStyle(probe).color;"
                + "probe.remove();"
                + "return color;"
                + "}"
                + "function notify(){try{"
                + "var rootStyle=getComputedStyle(document.documentElement);"
                + "var bodyStyle=document.body?getComputedStyle(document.body):null;"
                + "var value=(rootStyle.getPropertyValue('--surface-background')||rootStyle.getPropertyValue('--background')||(bodyStyle&&bodyStyle.backgroundColor)||'#faf8f1').trim();"
                + "var color=normalizeColor(value);"
                + "if(window.OpenChamberAndroid&&window.OpenChamberAndroid.setSystemBarColor){window.OpenChamberAndroid.setSystemBarColor(color);}"
                + "}catch(e){}}"
                + "function schedule(){clearTimeout(pending);pending=setTimeout(notify,50);}"
                + "window.__openchamberAndroidNotifySystemBars=notify;"
                + "new MutationObserver(schedule).observe(document.documentElement,{attributes:true,attributeFilter:['class','style','data-theme']});"
                + "if(document.head){new MutationObserver(schedule).observe(document.head,{childList:true,subtree:true,characterData:true});}"
                + "if(window.matchMedia){var mq=window.matchMedia('(prefers-color-scheme: dark)');if(mq.addEventListener)mq.addEventListener('change',schedule);else if(mq.addListener)mq.addListener(schedule);}"
                + "notify();setTimeout(notify,100);setTimeout(notify,500);"
                + "})();";
        view.evaluateJavascript(script, null);
    }

    private final class AndroidThemeBridge {
        @JavascriptInterface
        public void setSystemBarColor(String colorValue) {
            final int color = parseCssColor(colorValue, Color.rgb(250, 248, 241));
            final boolean darkIcons = shouldUseDarkSystemBarIcons(color);
            runOnUiThread(() -> applySystemBars(color, darkIcons));
        }

        @JavascriptInterface
        public String getPreference(String key) {
            if (!isAllowedWebPreferenceKey(key)) return "";
            return prefs.getString(PREF_WEB_PREFIX + key, "");
        }

        @JavascriptInterface
        public void setPreference(String key, String value) {
            if (!isAllowedWebPreferenceKey(key)) return;
            prefs.edit().putString(PREF_WEB_PREFIX + key, value == null ? "" : value).apply();
        }
    }

    private boolean isAllowedWebPreferenceKey(String key) {
        return LOCALE_STORAGE_KEY.equals(key);
    }

    private JSONObject stateJson() throws JSONException {
        JSONObject state = new JSONObject();
        JSONArray servers = new JSONArray();
        for (Server server : readServers()) servers.put(server.toJson());
        state.put("android", true);
        state.put("configured", getCurrentServer() != null);
        String currentId = prefs.getString(PREF_CURRENT_ID, "");
        state.put("currentServerId", currentId == null || currentId.isEmpty() ? JSONObject.NULL : currentId);
        state.put("servers", servers);
        return state;
    }

    private List<Server> readServers() {
        List<Server> servers = new ArrayList<>();
        String raw = prefs.getString(PREF_SERVERS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int index = 0; index < array.length(); index += 1) {
                Server server = Server.fromJson(array.optJSONObject(index));
                if (server != null) servers.add(server);
            }
        } catch (JSONException ignored) {
        }
        return servers;
    }

    private void writeServers(List<Server> servers) {
        prefs.edit().putString(PREF_SERVERS, toServersJson(servers)).apply();
    }

    private String toServersJson(List<Server> servers) {
        JSONArray array = new JSONArray();
        for (Server server : servers) array.put(server.toJson());
        return array.toString();
    }

    private Server getCurrentServer() {
        String id = prefs.getString(PREF_CURRENT_ID, "");
        if (id == null || id.isEmpty()) return null;
        for (Server server : readServers()) {
            if (id.equals(server.id)) return server;
        }
        return null;
    }

    private Server findServer(String id) {
        if (id == null || id.isEmpty()) return null;
        for (Server server : readServers()) {
            if (id.equals(server.id)) return server;
        }
        return null;
    }

    private boolean deleteServer(String id) {
        if (id == null || id.isEmpty()) return false;
        List<Server> servers = readServers();
        boolean removed = false;
        for (Iterator<Server> iterator = servers.iterator(); iterator.hasNext();) {
            if (id.equals(iterator.next().id)) {
                iterator.remove();
                removed = true;
                break;
            }
        }
        if (!removed) return false;
        SharedPreferences.Editor editor = prefs.edit().putString(PREF_SERVERS, toServersJson(servers));
        if (id.equals(prefs.getString(PREF_CURRENT_ID, ""))) editor.remove(PREF_CURRENT_ID);
        editor.apply();
        return true;
    }

    private Server upsertServer(String id, String label, String url, String password) {
        String normalized = normalizeServerUrl(url);
        if (normalized == null) return null;
        List<Server> servers = readServers();
        Server next = null;
        for (int index = 0; index < servers.size(); index += 1) {
            Server current = servers.get(index);
            if ((id != null && !id.isEmpty() && id.equals(current.id)) || normalized.equals(current.url)) {
                next = new Server(current.id, label == null || label.trim().isEmpty() ? normalized : label.trim(), normalized, password == null ? current.password : password);
                servers.set(index, next);
                break;
            }
        }
        if (next == null) {
            next = new Server(UUID.randomUUID().toString(), label == null || label.trim().isEmpty() ? normalized : label.trim(), normalized, password == null ? "" : password);
            servers.add(next);
        }
        writeServers(servers);
        return next;
    }

    private String normalizeServerUrl(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        if (!trimmed.contains("://")) trimmed = "http://" + trimmed;
        try {
            URL url = new URL(trimmed);
            String protocol = url.getProtocol();
            if (!"http".equals(protocol) && !"https".equals(protocol)) return null;
            StringBuilder origin = new StringBuilder(protocol).append("://").append(url.getHost());
            if (url.getPort() > 0) origin.append(':').append(url.getPort());
            return origin.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean probeHealth(String baseUrl) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(baseUrl + "/health").openConnection();
            connection.setConnectTimeout(REQUEST_TIMEOUT_MS);
            connection.setReadTimeout(REQUEST_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            return status >= 200 && status < 300;
        } catch (IOException ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private List<String> getHeaderValues(HttpURLConnection connection, String headerName) {
        List<String> values = new ArrayList<>();
        Map<String, List<String>> headers = connection.getHeaderFields();
        if (headers == null) return values;
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            String key = header.getKey();
            if (key == null || !key.equalsIgnoreCase(headerName)) continue;
            List<String> headerValues = header.getValue();
            if (headerValues != null) values.addAll(headerValues);
        }
        return values;
    }

    private String rewriteCookieForLocalWebView(String setCookie) {
        if (setCookie == null) return "";
        String[] parts = setCookie.split(";");
        StringBuilder rewritten = new StringBuilder();
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.startsWith("domain=") || lower.equals("secure")) continue;
            if (lower.startsWith("samesite=none")) continue;
            if (rewritten.length() > 0) rewritten.append("; ");
            rewritten.append(trimmed);
        }
        return rewritten.toString();
    }

    private void storeCookiesForLocalWebView(List<String> setCookies) {
        if (setCookies == null || setCookies.isEmpty() || localServer == null) return;
        CookieManager cookieManager = CookieManager.getInstance();
        String localOrigin = localServer.origin();
        for (String setCookie : setCookies) {
            String rewritten = rewriteCookieForLocalWebView(setCookie);
            if (!rewritten.isEmpty()) cookieManager.setCookie(localOrigin, rewritten);
        }
        cookieManager.flush();
    }

    private void clearLocalWebViewCookies() {
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.removeAllCookies(null);
        cookieManager.flush();
    }

    private LoginResult login(String baseUrl, String password) {
        if (password == null || password.isEmpty()) return new LoginResult(true, 200);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(baseUrl + "/auth/session").openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(REQUEST_TIMEOUT_MS);
            connection.setReadTimeout(REQUEST_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            JSONObject body = new JSONObject();
            body.put("password", password);
            body.put("trustDevice", true);
            body.put("issueClientToken", false);
            body.put("clientLabel", "OpenChamber Android");
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream stream = connection.getOutputStream()) {
                stream.write(payload);
            }
            int status = connection.getResponseCode();
            if (status >= 200 && status < 300) {
                storeCookiesForLocalWebView(getHeaderValues(connection, "Set-Cookie"));
            }
            return new LoginResult(status >= 200 && status < 300, status);
        } catch (Exception ignored) {
            return new LoginResult(false, 0);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private final class LocalServer {
        private final ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        private volatile boolean running = true;

        LocalServer() throws IOException {
        }

        void start() {
            workers.execute(() -> {
                while (running) {
                    try {
                        Socket socket = serverSocket.accept();
                        workers.execute(() -> handle(socket));
                    } catch (IOException ignored) {
                        if (running) break;
                    }
                }
            });
        }

        void stop() {
            running = false;
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }

        String origin() {
            return "http://127.0.0.1:" + serverSocket.getLocalPort();
        }

        String url(String path) {
            return origin() + path;
        }

        private void handle(Socket socket) {
            try (Socket closeable = socket) {
                closeable.setSoTimeout(0);
                BufferedInputStream input = new BufferedInputStream(closeable.getInputStream());
                OutputStream output = closeable.getOutputStream();
                Request request = readRequest(input);
                if (request == null) return;
                if (request.path.startsWith("/__android/")) {
                    handleAndroidEndpoint(request, output);
                } else if (isProxyPath(request.path)) {
                    if (isWebSocket(request)) proxyWebSocket(request, input, output);
                    else proxyHttp(request, output);
                } else {
                    serveAsset(request, output);
                }
            } catch (Exception ignored) {
            }
        }

        private boolean isProxyPath(String path) {
            return path.equals("/health") || path.startsWith("/auth/") || path.equals("/api") || path.startsWith("/api/") || path.startsWith("/global/");
        }

        private void handleAndroidEndpoint(Request request, OutputStream output) throws IOException, JSONException {
            if ("GET".equals(request.method) && "/__android/state".equals(request.path)) {
                writeJson(output, 200, stateJson());
                return;
            }
            if ("POST".equals(request.method) && "/__android/connect".equals(request.path)) {
                JSONObject body = request.body.length == 0 ? new JSONObject() : new JSONObject(new String(request.body, StandardCharsets.UTF_8));
                Server server = findServer(body.optString("id", ""));
                if (server == null) {
                    server = upsertServer("", body.optString("label", ""), body.optString("url", ""), body.has("password") ? body.optString("password", "") : null);
                }
                if (server == null) {
                    writeError(output, 400, "Invalid server address");
                    return;
                }
                if (!probeHealth(server.url)) {
                    writeError(output, 502, "Unable to reach server");
                    return;
                }
                LoginResult login = login(server.url, server.password);
                if (!login.success && (login.statusCode == 401 || login.statusCode == 403 || body.has("password"))) {
                    writeError(output, 401, "Login failed");
                    return;
                }
                prefs.edit().putString(PREF_CURRENT_ID, server.id).apply();
                JSONObject response = new JSONObject();
                response.put("state", stateJson());
                writeJson(output, 200, response);
                return;
            }
            if ("POST".equals(request.method) && "/__android/update-server".equals(request.path)) {
                JSONObject body = request.body.length == 0 ? new JSONObject() : new JSONObject(new String(request.body, StandardCharsets.UTF_8));
                String id = body.optString("id", "");
                if (id.isEmpty() || findServer(id) == null) {
                    writeError(output, 404, "Server not found");
                    return;
                }
                Server server = upsertServer(id, body.optString("label", ""), body.optString("url", ""), body.has("password") ? body.optString("password", "") : null);
                if (server == null) {
                    writeError(output, 400, "Invalid server address");
                    return;
                }
                JSONObject response = new JSONObject();
                response.put("state", stateJson());
                writeJson(output, 200, response);
                return;
            }
            if ("POST".equals(request.method) && "/__android/logout".equals(request.path)) {
                prefs.edit().remove(PREF_CURRENT_ID).apply();
                clearLocalWebViewCookies();
                JSONObject response = new JSONObject();
                response.put("state", stateJson());
                writeJson(output, 200, response);
                return;
            }
            if ("POST".equals(request.method) && "/__android/delete-server".equals(request.path)) {
                JSONObject body = request.body.length == 0 ? new JSONObject() : new JSONObject(new String(request.body, StandardCharsets.UTF_8));
                if (!deleteServer(body.optString("id", ""))) {
                    writeError(output, 404, "Server not found");
                    return;
                }
                JSONObject response = new JSONObject();
                response.put("state", stateJson());
                writeJson(output, 200, response);
                return;
            }
            writeError(output, 404, "Not found");
        }

        private void serveAsset(Request request, OutputStream output) throws IOException {
            if (!"GET".equals(request.method) && !"HEAD".equals(request.method)) {
                writePlain(output, 405, "Method not allowed");
                return;
            }
            String cleanPath = request.path.equals("/") ? "/mobile.html" : request.path;
            if (cleanPath.contains("..")) {
                writePlain(output, 400, "Bad request");
                return;
            }
            String assetPath = "www" + URLDecoder.decode(cleanPath, "UTF-8");
            try (InputStream asset = getAssets().open(assetPath)) {
                byte[] bytes = readAll(asset);
                writeHeaders(output, 200, mimeType(assetPath), bytes.length, null);
                if (!"HEAD".equals(request.method)) output.write(bytes);
            } catch (IOException ignored) {
                writePlain(output, 404, "Not found");
            }
        }

        private void proxyHttp(Request request, OutputStream output) throws IOException {
            Server server = getCurrentServer();
            if (server == null) {
                writePlain(output, 503, "OpenChamber server is not configured");
                return;
            }
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(server.url + request.pathWithQuery).openConnection();
                connection.setRequestMethod(request.method);
                connection.setConnectTimeout(REQUEST_TIMEOUT_MS);
                connection.setReadTimeout(0);
                for (Map.Entry<String, String> header : request.headers.entrySet()) {
                    String key = header.getKey();
                    if (key.equalsIgnoreCase("host") || key.equalsIgnoreCase("connection") || key.equalsIgnoreCase("content-length") || key.equalsIgnoreCase("accept-encoding") || key.equalsIgnoreCase("origin")) continue;
                    connection.setRequestProperty(key, header.getValue());
                }
                connection.setRequestProperty("Origin", server.url);
                if (request.body.length > 0) {
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(request.body.length);
                    try (OutputStream requestOut = connection.getOutputStream()) {
                        requestOut.write(request.body);
                    }
                }
                int status = connection.getResponseCode();
                String message = connection.getResponseMessage();
                writeStatusLine(output, status, message == null ? "OK" : message);
                List<String> setCookies = getHeaderValues(connection, "Set-Cookie");
                storeCookiesForLocalWebView(setCookies);
                for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                    if (header.getKey() == null) continue;
                    String key = header.getKey();
                    if (key.equalsIgnoreCase("connection") || key.equalsIgnoreCase("transfer-encoding") || key.equalsIgnoreCase("content-encoding") || key.equalsIgnoreCase("set-cookie")) continue;
                    for (String value : header.getValue()) {
                        output.write((key + ": " + value + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    }
                }
                for (String setCookie : setCookies) {
                    String rewritten = rewriteCookieForLocalWebView(setCookie);
                    if (!rewritten.isEmpty()) {
                        output.write(("Set-Cookie: " + rewritten + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    }
                }
                output.write("Connection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                InputStream body = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                if (body != null) copy(body, output);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }

        private void proxyWebSocket(Request request, BufferedInputStream clientInput, OutputStream clientOutput) throws IOException {
            Server server = getCurrentServer();
            if (server == null) {
                writePlain(clientOutput, 503, "OpenChamber server is not configured");
                return;
            }
            URL target = new URL(server.url + request.pathWithQuery);
            int port = target.getPort() > 0 ? target.getPort() : ("https".equals(target.getProtocol()) ? 443 : 80);
            if ("https".equals(target.getProtocol())) {
                writePlain(clientOutput, 501, "Secure WebSocket proxy is unavailable for this debug client");
                return;
            }
            Socket upstream = new Socket(target.getHost(), port);
            OutputStream upstreamOut = upstream.getOutputStream();
            upstreamOut.write((request.method + " " + request.pathWithQuery + " HTTP/1.1\r\n").getBytes(StandardCharsets.ISO_8859_1));
            for (Map.Entry<String, String> header : request.headers.entrySet()) {
                if (header.getKey().equalsIgnoreCase("host")) {
                    upstreamOut.write(("Host: " + target.getHost() + (target.getPort() > 0 ? ":" + target.getPort() : "") + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                } else if (header.getKey().equalsIgnoreCase("origin")) {
                    upstreamOut.write(("Origin: " + server.url + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                } else {
                    upstreamOut.write((header.getKey() + ": " + header.getValue() + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                }
            }
            upstreamOut.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
            upstreamOut.flush();
            workers.execute(() -> {
                try {
                    copy(clientInput, upstream.getOutputStream());
                } catch (IOException ignored) {
                }
            });
            try {
                copy(upstream.getInputStream(), clientOutput);
            } finally {
                try {
                    upstream.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private Request readRequest(BufferedInputStream input) throws IOException {
        String line = readLine(input);
        if (line == null || line.isEmpty()) return null;
        String[] parts = line.split(" ", 3);
        if (parts.length < 2) return null;
        Map<String, String> headers = new LinkedHashMap<>();
        while ((line = readLine(input)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) headers.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        int length = 0;
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (header.getKey().equalsIgnoreCase("content-length")) {
                try {
                    length = Integer.parseInt(header.getValue());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int next = input.read(body, read, length - read);
            if (next < 0) break;
            read += next;
        }
        return new Request(parts[0], parts[1], headers, body);
    }

    private String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int current;
        while ((current = input.read()) != -1) {
            if (current == '\n') break;
            if (current != '\r') buffer.write(current);
        }
        if (current == -1 && buffer.size() == 0) return null;
        return buffer.toString("ISO-8859-1");
    }

    private boolean isWebSocket(Request request) {
        String upgrade = request.headers.get("Upgrade");
        if (upgrade == null) upgrade = request.headers.get("upgrade");
        return upgrade != null && "websocket".equalsIgnoreCase(upgrade);
    }

    private byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copy(input, output);
        return output.toByteArray();
    }

    private void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            output.flush();
        }
    }

    private String mimeType(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    private void writeJson(OutputStream output, int status, JSONObject json) throws IOException {
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        writeHeaders(output, status, "application/json; charset=utf-8", body.length, null);
        output.write(body);
    }

    private void writeError(OutputStream output, int status, String message) throws IOException, JSONException {
        JSONObject error = new JSONObject();
        error.put("error", message);
        writeJson(output, status, error);
    }

    private void writePlain(OutputStream output, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        writeHeaders(output, status, "text/plain; charset=utf-8", body.length, null);
        output.write(body);
    }

    private void writeHeaders(OutputStream output, int status, String contentType, int contentLength, Map<String, String> extra) throws IOException {
        writeStatusLine(output, status, status == 200 ? "OK" : "Error");
        output.write(("Content-Type: " + contentType + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        output.write(("Content-Length: " + contentLength + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        output.write("Connection: close\r\n".getBytes(StandardCharsets.ISO_8859_1));
        if (extra != null) {
            for (Map.Entry<String, String> header : extra.entrySet()) {
                output.write((header.getKey() + ": " + header.getValue() + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            }
        }
        output.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
    }

    private void writeStatusLine(OutputStream output, int status, String message) throws IOException {
        output.write(("HTTP/1.1 " + status + " " + message + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
    }

    private static final class Request {
        final String method;
        final String pathWithQuery;
        final String path;
        final Map<String, String> headers;
        final byte[] body;

        Request(String method, String pathWithQuery, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.pathWithQuery = pathWithQuery;
            int query = pathWithQuery.indexOf('?');
            this.path = query >= 0 ? pathWithQuery.substring(0, query) : pathWithQuery;
            this.headers = headers;
            this.body = body;
        }
    }

    private static final class Server {
        final String id;
        final String label;
        final String url;
        final String password;

        Server(String id, String label, String url, String password) {
            this.id = id;
            this.label = label;
            this.url = url;
            this.password = password;
        }

        static Server fromJson(JSONObject object) {
            if (object == null) return null;
            String id = object.optString("id", "");
            String url = object.optString("url", "");
            if (id.isEmpty() || url.isEmpty()) return null;
            return new Server(id, object.optString("label", url), url, object.optString("password", ""));
        }

        JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("id", id);
                object.put("label", label);
                object.put("url", url);
                object.put("password", password);
            } catch (JSONException ignored) {
            }
            return object;
        }
    }

    private static final class LoginResult {
        final boolean success;
        final int statusCode;

        LoginResult(boolean success, int statusCode) {
            this.success = success;
            this.statusCode = statusCode;
        }
    }
}
