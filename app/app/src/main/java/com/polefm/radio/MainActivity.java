package com.polefm.radio;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ProgressBar;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    public static final String ACTION_STOP = "com.polefm.radio.STOP";
    private static final String URL = "https://polefm.mooo.com/";

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int NOTIFICATION_PERMISSION_CODE = 200;

    // Экран загрузки (логотип), скрывается после загрузки сайта
    private View splash;
    private boolean splashHidden = false;

    // Полоса загрузки на заставке
    private ProgressBar splashProgress;
    private ValueAnimator splashFillAnimator;
    private long splashStartAt;

    private static final long MIN_SPLASH_MS = 2500;   // минимальное время показа заставки
    private static final long SPLASH_FILL_MS = 2200;  // заполнение полосы до 90%

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Handle STOP action from notification
        if (MainActivity.ACTION_STOP.equals(getIntent().getAction())) {
            stopRadioService();
            finish();
            return;
        }

        webView = findViewById(R.id.webview);
        splash = findViewById(R.id.splash);
        splashProgress = findViewById(R.id.splash_progress);
        splashStartAt = SystemClock.uptimeMillis();
        startSplashFill();

        // Направляем кнопки громкости на музыкальный поток
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(settings.getUserAgentString() + " PoleFM/1.0");

        webView.setWebViewClient(new WebViewClient() {
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri != null && uri.getHost() != null
                        && (uri.getHost().equals("polefm.mooo.com")
                        || uri.getHost().endsWith(".polefm.mooo.com"))) {
                    return false;
                }
                Intent i = new Intent(Intent.ACTION_VIEW, uri);
                startActivity(i);
                return true;
            }

            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Страница загрузилась (или показана страница ошибки) — завершаем заставку
                finishSplash();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            public void onPermissionRequest(final PermissionRequest request) {
                // Grant all requested permissions (audio, geolocation, etc.)
                runOnUiThread(() -> request.grant(request.getResources()));
            }

            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams fileChooserParams) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, 1001);
                } catch (android.content.ActivityNotFoundException e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        if (savedInstanceState == null) {
            webView.loadUrl(URL);
        } else {
            webView.restoreState(savedInstanceState);
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        requestNotificationPermission();
        startForegroundService();
    }

    /**
     * Плавно заполняет полосу загрузки до 90% за SPLASH_FILL_MS.
     * Оставшиеся 10% добавляются после загрузки страницы.
     */
    private void startSplashFill() {
        if (splashProgress == null) return;
        splashFillAnimator = ValueAnimator.ofInt(0, 90);
        splashFillAnimator.setDuration(SPLASH_FILL_MS);
        splashFillAnimator.addUpdateListener(
                a -> splashProgress.setProgress((int) a.getAnimatedValue()));
        splashFillAnimator.start();
    }

    /**
     * Страница загрузилась: держим заставку минимум MIN_SPLASH_MS,
     * затем доводим полосу до 100% и плавно скрываем экран загрузки.
     */
    private void finishSplash() {
        if (splashHidden || splash == null) return;
        long elapsed = SystemClock.uptimeMillis() - splashStartAt;
        long delay = Math.max(0, MIN_SPLASH_MS - elapsed);
        splash.postDelayed(this::completeSplash, delay);
    }

    /** Доводит полосу загрузки до 100% и скрывает заставку. */
    private void completeSplash() {
        if (splashHidden || splash == null || splashProgress == null) return;
        if (splashFillAnimator != null) {
            splashFillAnimator.cancel();
            splashFillAnimator = null;
        }
        ValueAnimator finish = ValueAnimator.ofInt(splashProgress.getProgress(), 100);
        finish.setDuration(350);
        finish.addUpdateListener(
                a -> splashProgress.setProgress((int) a.getAnimatedValue()));
        finish.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                hideSplash();
            }
        });
        finish.start();
    }

    /**
     * Скрывает экран загрузки с плавным затуханием (один раз, при первой загрузке).
     */
    private void hideSplash() {
        if (splashHidden || splash == null) return;
        splashHidden = true;
        splash.animate()
                .alpha(0f)
                .setDuration(400)
                .withEndAction(() -> {
                    if (splash != null) {
                        splash.setVisibility(View.GONE);
                    }
                })
                .start();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                        NOTIFICATION_PERMISSION_CODE);
            }
        }
    }

    private void startForegroundService() {
        Intent intent = new Intent(this, RadioForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void stopRadioService() {
        Intent intent = new Intent(this, RadioForegroundService.class);
        stopService(intent);
    }

    /**
     * Медиа-клавиши гарнитуры (смартфон): транслируются в функции веб-плеера сайта
     * (play / pause / stop). Остальные клавиши обрабатываются системой как обычно.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (webView != null && event.getAction() == KeyEvent.ACTION_DOWN) {
            final int code = event.getKeyCode();
            switch (code) {
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_HEADSETHOOK:
                    execJs("(function(){ if (typeof audioEl !== 'undefined' && audioEl) {"
                            + " if (audioEl.paused) { if (typeof mpcCmd === 'function') mpcCmd('play'); }"
                            + " else { if (typeof mpcCmd === 'function') mpcCmd('pause'); } } })();");
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                    execJs("(function(){ if (typeof mpcCmd === 'function') mpcCmd('play'); })();");
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    execJs("(function(){ if (typeof mpcCmd === 'function') mpcCmd('pause'); })();");
                    return true;
                case KeyEvent.KEYCODE_MEDIA_STOP:
                    execJs("(function(){ if (typeof mpcCmd === 'function') mpcCmd('stop'); })();");
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /** Выполнение JavaScript в WebView. */
    private void execJs(final String js) {
        if (webView == null) return;
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopRadioService();

        if (splashFillAnimator != null) {
            splashFillAnimator.cancel();
            splashFillAnimator = null;
        }
        splash = null;
        splashProgress = null;

        if (webView != null) {
            webView.destroy();
            webView = null;
        }

        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && filePathCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }
}