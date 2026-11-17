package ru.sev.risodiary;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    private static final String BASE_URL = "https://riso.sev.gov.ru";
    private static final String URL_DIARY = BASE_URL + "/journal-app/u.11553";
    private static final String URL_GRADES = BASE_URL + "/journal-student-grades-action/u.11553";
    private static final String URL_INBOX = BASE_URL + "/journal-messages-action";
    private static final String URL_SENT = BASE_URL + "/journal-messages-action/category.sent";
    private static final String URL_BOARD = BASE_URL + "/journal-board-action";

    private WebView uiWebView;
    private Dialog authDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);

        uiWebView = new WebView(this);
        WebSettings settings = uiWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        cookieManager.setAcceptThirdPartyCookies(uiWebView, true);

        uiWebView.addJavascriptInterface(new AndroidRisoBridge(), "AndroidRisoBridge");
        uiWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri != null && "https".equals(uri.getScheme())) {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    return true;
                }
                return false;
            }
        });

        setContentView(uiWebView);
        uiWebView.loadUrl("file:///android_asset/school_diary.html");
    }

    public class AndroidRisoBridge {
        @JavascriptInterface
        public void fetchWeekData(final int weekOffset) {
            final String cookies = CookieManager.getInstance().getCookie(BASE_URL);
            if (cookies == null || (!cookies.contains("eljur") && !cookies.contains("session") && !cookies.contains("auth") && !cookies.contains("PHPSESSID"))) {
                runOnUiThread(() -> showGosuslugiLoginDialog(weekOffset));
                return;
            }
            new Thread(() -> downloadAllRisoSections(weekOffset, cookies)).start();
        }
    }

    private void showGosuslugiLoginDialog(final int weekOffset) {
        if (authDialog != null && authDialog.isShowing()) return;

        authDialog = new Dialog(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        WebView authWebView = new WebView(this);
        WebSettings s = authWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(authWebView, true);

        authWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && url.startsWith(BASE_URL + "/journal-") && !url.contains("authorize") && !url.contains("login")) {
                    CookieManager.getInstance().flush();
                    String cookies = CookieManager.getInstance().getCookie(BASE_URL);
                    if (authDialog != null && authDialog.isShowing()) {
                        authDialog.dismiss();
                    }
                    new Thread(() -> downloadAllRisoSections(weekOffset, cookies)).start();
                }
            }
        });

        authWebView.loadUrl(URL_DIARY);
        authDialog.setContentView(authWebView);
        authDialog.show();
    }

    private String fetchHtml(String targetUrl, String cookies) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(targetUrl).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Cookie", cookies);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36");
        conn.setInstanceFollowRedirects(false);

        int code = conn.getResponseCode();
        if (code == 301 || code == 302 || code == 401 || code == 403) {
            throw new SecurityException("SESSION_EXPIRED");
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        return sb.toString();
    }

    private void downloadAllRisoSections(int weekOffset, String cookies) {
        try {
            // В РИСО / ЭлЖур: week.1 = прошлая неделя (-1), week.-1 = следующая неделя (+1)
            String scheduleUrl = URL_DIARY + (weekOffset != 0 ? "/week." + (-weekOffset) : "");
            String scheduleHtml = fetchHtml(scheduleUrl, cookies);
            String gradesHtml = fetchHtml(URL_GRADES, cookies);
            String inboxHtml = fetchHtml(URL_INBOX, cookies);
            String sentHtml = fetchHtml(URL_SENT, cookies);
            String boardHtml = fetchHtml(URL_BOARD, cookies);

            JSONObject payload = new JSONObject();
            payload.put("weekOffset", weekOffset);
            payload.put("scheduleHtml", scheduleHtml);
            payload.put("gradesHtml", gradesHtml);
            payload.put("inboxHtml", inboxHtml);
            payload.put("sentHtml", sentHtml);
            payload.put("boardHtml", boardHtml);

            final String jsCall = "window.onRisoHtmlReceived(" + JSONObject.quote(payload.toString()) + ");";
            runOnUiThread(() -> uiWebView.evaluateJavascript(jsCall, null));
        } catch (SecurityException authErr) {
            runOnUiThread(() -> showGosuslugiLoginDialog(weekOffset));
        } catch (Exception ignored) {
            // При отсутствии сети работает локальный кэш
        }
    }
}
