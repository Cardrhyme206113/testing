package com.example.blockhost;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public final class BlockHostActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 1101;
    private WebView webView;
    private ValueCallback<Uri[]> pendingFiles;
    private BlockHostBackend backend;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RuntimeRepairInvoker.run(this);
        backend = new BlockHostBackend(this);
        webView = new WebView(this);
        setContentView(webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        WebView.setWebContentsDebuggingEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pendingFiles != null) pendingFiles.onReceiveValue(null);
                pendingFiles = callback;
                try {
                    Intent intent = params.createIntent();
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception e) {
                    pendingFiles = null;
                    return false;
                }
            }
        });
        webView.addJavascriptInterface(new BackendBridge(), "AndroidBackend");
        webView.addJavascriptInterface(new UiBridge(), "AndroidUi");
        setSystemTheme(false);
        webView.loadUrl("file:///android_asset/index.html");
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
        }
    }

    public final class BackendBridge {
        @JavascriptInterface public String call(String method, String payloadJson) { return backend.call(method, payloadJson); }
    }
    public final class UiBridge {
        @JavascriptInterface public void setDarkMode(boolean dark) { runOnUiThread(() -> setSystemTheme(dark)); }
    }

    private void setSystemTheme(boolean dark) {
        int color = Color.parseColor(dark ? "#0F1310" : "#EFF4EF");
        getWindow().setStatusBarColor(color);
        getWindow().setNavigationBarColor(color);
        int flags = getWindow().getDecorView().getSystemUiVisibility();
        if (dark) flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || pendingFiles == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                result = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) result[i] = clip.getItemAt(i).getUri();
            } else if (data.getData() != null) result = new Uri[]{data.getData()};
        }
        pendingFiles.onReceiveValue(result);
        pendingFiles = null;
    }

    @Override public void onBackPressed() {
        webView.evaluateJavascript("(function(){const m=document.querySelector('.modalShade.open');if(m){m.classList.remove('open');return 'handled'}const v=document.querySelector('.view.active');if(v&&v.dataset.view!='home'){document.querySelector('[data-nav=home]').click();return 'handled'}return 'exit'})()", value -> { if ("\"exit\"".equals(value)) BlockHostActivity.super.onBackPressed(); });
    }
}
