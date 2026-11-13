package com.model90.metronome;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStructure;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeProvider;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int COLOR_DARK_STAGE = Color.parseColor("#0B0C0F");
    private static final int COLOR_LIGHT_STAGE = Color.parseColor("#D8DCE2");

    private FrameLayout rootContainer;
    private FastWebView webView;
    private boolean interceptBack = false;
    private boolean backCallbackRegistered = false;
    private Object backInvokedCallback;

    private boolean isLightStage = false;
    private boolean isMetronomePlaying = false;
    private boolean allowBackgroundPlay = false;
    private boolean webViewPaused = false;
    private boolean isActivityStarted = false;
    private boolean pageLoaded = false;

    private float safeLeftDp = 0f;
    private float safeTopDp = 0f;
    private float safeRightDp = 0f;
    private float safeBottomDp = 0f;

    /**
     * [1] 삼성 One UI 보조 서비스 사용 시 Chromium Accessibility / ContentCapture / Autofill 동기 블로킹 차단.
     * [2] 백그라운드 동작 옵션이 켜진 상태로 재생 중일 때만 창 가시성 숨김에 의한 오디오 중단 방어.
     */
    private static final class FastWebView extends WebView {
        private boolean keepAliveInBackground = false;

        FastWebView(Context context) {
            super(context);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Api30Impl.disableContentCapture(this);
            }
        }

        void setKeepAliveInBackground(boolean keepAlive) {
            this.keepAliveInBackground = keepAlive;
        }

        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            if (visibility != View.VISIBLE && keepAliveInBackground) {
                super.onWindowVisibilityChanged(View.VISIBLE);
            } else {
                super.onWindowVisibilityChanged(visibility);
            }
        }

        @Override
        public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return null;
        }

        @Override
        public void onProvideVirtualStructure(ViewStructure structure) {
        }

        @Override
        public void onProvideAutofillVirtualStructure(ViewStructure structure, int flags) {
        }

        @Override
        public void sendAccessibilityEvent(int eventType) {
        }

        @Override
        public void sendAccessibilityEventUnchecked(AccessibilityEvent event) {
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        isLightStage = !isDeviceInDarkMode(getResources().getConfiguration());
        int initialBgColor = isLightStage ? COLOR_LIGHT_STAGE : COLOR_DARK_STAGE;

        Window window = getWindow();
        configureEdgeToEdgeAndCutout(window);
        enableHighRefreshRate(window);

        rootContainer = new FrameLayout(this);
        rootContainer.setBackgroundColor(initialBgColor);
        observeSafeAreaInsets(rootContainer);

        webView = new FastWebView(this);
        webView.setBackgroundColor(initialBgColor);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        if (Build.VERSION.SDK_INT >= 35) {
            Api35Impl.requestHighFrameRate(window, webView);
        }

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setTextZoom(100);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Api33Impl.disableAlgorithmicDarkening(settings);
        }

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request != null ? request.getUrl() : null;
                String url = uri != null ? uri.toString() : "";
                return !url.startsWith("file:///android_asset/");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                pushSafeAreaInsetsToCss();
            }
        });

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        rootContainer.addView(
            webView,
            new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        );
        setContentView(rootContainer);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backInvokedCallback = Api33Impl.createCallback(this::dispatchBackToWebView);
        }

        applyStageTheme(isLightStage);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private static boolean isDeviceInDarkMode(Configuration config) {
        if (config == null) return true;
        return (config.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private void syncBackgroundKeepAliveFlag() {
        if (webView != null) {
            webView.setKeepAliveInBackground(isMetronomePlaying && allowBackgroundPlay);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        isActivityStarted = true;
        resumeWebViewIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumeWebViewIfNeeded();
        Window window = getWindow();
        enableHighRefreshRate(window);
        if (Build.VERSION.SDK_INT >= 35 && webView != null) {
            Api35Impl.requestHighFrameRate(window, webView);
        }
        applyStageTheme(isLightStage);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            Window window = getWindow();
            enableHighRefreshRate(window);
            applyStageTheme(isLightStage);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean systemDark = isDeviceInDarkMode(newConfig);
        if (webView != null && pageLoaded) {
            webView.evaluateJavascript(
                "window.__model90OnSystemThemeChange && window.__model90OnSystemThemeChange(" + systemDark + ")",
                null
            );
        }
        if (rootContainer != null) {
            rootContainer.requestApplyInsets();
        }
        applyStageTheme(isLightStage);
    }

    private void resumeWebViewIfNeeded() {
        if (webView != null && webViewPaused) {
            webView.onResume();
            webView.resumeTimers();
            webViewPaused = false;
        }
    }

    private void pauseWebViewCleanly() {
        if (webView != null && !webViewPaused) {
            webView.onPause();
            webView.pauseTimers();
            webViewPaused = true;
        }
    }

    @Override
    protected void onStop() {
        isActivityStarted = false;
        syncBackgroundKeepAliveFlag();
        if (webView != null && !(isMetronomePlaying && allowBackgroundPlay)) {
            if (isMetronomePlaying) {
                isMetronomePlaying = false;
                syncBackgroundKeepAliveFlag();
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                webView.evaluateJavascript(
                    "window.__model90StopPlayback && window.__model90StopPlayback()",
                    result -> {
                        if (!isActivityStarted) {
                            pauseWebViewCleanly();
                        }
                    }
                );
            } else {
                pauseWebViewCleanly();
            }
        }
        super.onStop();
    }

    @SuppressWarnings("deprecation")
    private void enableHighRefreshRate(Window window) {
        Display display = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            ? Api30Impl.getDisplay(this)
            : getWindowManager().getDefaultDisplay();
        if (display == null) return;

        Display.Mode[] modes = display.getSupportedModes();
        Display.Mode current = display.getMode();
        int bestModeId = current.getModeId();
        float maxRefresh = current.getRefreshRate();

        for (Display.Mode mode : modes) {
            if (mode.getPhysicalWidth() == current.getPhysicalWidth()
                && mode.getPhysicalHeight() == current.getPhysicalHeight()
                && mode.getRefreshRate() > maxRefresh) {
                maxRefresh = mode.getRefreshRate();
                bestModeId = mode.getModeId();
            }
        }

        WindowManager.LayoutParams params = window.getAttributes();
        if (params.preferredDisplayModeId != bestModeId || params.preferredRefreshRate != maxRefresh) {
            params.preferredDisplayModeId = bestModeId;
            params.preferredRefreshRate = maxRefresh;
            window.setAttributes(params);
        }
    }

    @SuppressWarnings("deprecation")
    private void configureEdgeToEdgeAndCutout(Window window) {
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Api29Impl.disableSystemBarContrast(window);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Api30Impl.configureFullBleedWindow(window);
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Api28Impl.enableShortEdgesCutout(window);
            }
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }

    @SuppressWarnings("deprecation")
    private void observeSafeAreaInsets(View targetView) {
        targetView.setOnApplyWindowInsetsListener((v, insets) -> {
            float density = Math.max(1f, v.getResources().getDisplayMetrics().density);
            int leftPx;
            int topPx;
            int rightPx;
            int bottomPx;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = Api30Impl.getSafeInsets(insets);
                leftPx = bars.left;
                topPx = bars.top;
                rightPx = bars.right;
                bottomPx = bars.bottom;
            } else {
                leftPx = insets.getSystemWindowInsetLeft();
                topPx = insets.getSystemWindowInsetTop();
                rightPx = insets.getSystemWindowInsetRight();
                bottomPx = insets.getSystemWindowInsetBottom();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    int[] cutout = Api28Impl.getCutoutSafeInsets(insets);
                    leftPx = Math.max(leftPx, cutout[0]);
                    topPx = Math.max(topPx, cutout[1]);
                    rightPx = Math.max(rightPx, cutout[2]);
                    bottomPx = Math.max(bottomPx, cutout[3]);
                }
            }

            safeLeftDp = leftPx / density;
            safeTopDp = topPx / density;
            safeRightDp = rightPx / density;
            safeBottomDp = bottomPx / density;
            pushSafeAreaInsetsToCss();

            return insets;
        });
    }

    private void pushSafeAreaInsetsToCss() {
        if (webView == null || !pageLoaded) return;
        String js = String.format(
            Locale.US,
            "(function(){var s=document.documentElement.style;"
                + "s.setProperty('--native-safe-left','%.1fpx');"
                + "s.setProperty('--native-safe-top','%.1fpx');"
                + "s.setProperty('--native-safe-right','%.1fpx');"
                + "s.setProperty('--native-safe-bottom','%.1fpx');})();",
            safeLeftDp,
            safeTopDp,
            safeRightDp,
            safeBottomDp
        );
        webView.evaluateJavascript(js, null);
    }

    @SuppressWarnings("deprecation")
    private void applyStageTheme(boolean isLight) {
        isLightStage = isLight;
        int bgColor = isLight ? COLOR_LIGHT_STAGE : COLOR_DARK_STAGE;
        if (rootContainer != null) {
            rootContainer.setBackgroundColor(bgColor);
        }
        if (webView != null) {
            webView.setBackgroundColor(bgColor);
        }

        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Api30Impl.setSystemBarsLightAppearance(window, isLight);
        } else {
            View decor = window.getDecorView();
            int vis = decor.getSystemUiVisibility();
            if (isLight) {
                vis |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else {
                vis &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            }
            decor.setSystemUiVisibility(vis);
        }
    }

    private void updateBackRegistration(boolean shouldIntercept) {
        interceptBack = shouldIntercept;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backInvokedCallback != null) {
            if (shouldIntercept && !backCallbackRegistered) {
                Api33Impl.register(this, backInvokedCallback);
                backCallbackRegistered = true;
            } else if (!shouldIntercept && backCallbackRegistered) {
                Api33Impl.unregister(this, backInvokedCallback);
                backCallbackRegistered = false;
            }
        }
    }

    private void dispatchBackToWebView() {
        if (webView == null) {
            finish();
            return;
        }
        webView.evaluateJavascript(
            "Boolean(window.__model90HandleBack && window.__model90HandleBack())",
            result -> {
                if (!"true".equals(result)) {
                    updateBackRegistration(false);
                    finish();
                }
            }
        );
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (interceptBack) {
            dispatchBackToWebView();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        updateBackRegistration(false);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (webView != null) {
            webView.setKeepAliveInBackground(false);
            if (webViewPaused) {
                webView.onResume();
                webView.resumeTimers();
                webViewPaused = false;
            }
            webView.evaluateJavascript("window.__model90StopPlayback && window.__model90StopPlayback()", null);
            if (rootContainer != null) {
                rootContainer.removeView(webView);
            }
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    public final class AndroidBridge {

        @JavascriptInterface
        public boolean isSystemDarkMode() {
            return isDeviceInDarkMode(getResources().getConfiguration());
        }

        @JavascriptInterface
        public void setPlayingState(final boolean playing) {
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                isMetronomePlaying = playing;
                syncBackgroundKeepAliveFlag();
            });
        }

        @JavascriptInterface
        public void setBackgroundPlay(final boolean enabled) {
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                allowBackgroundPlay = enabled;
                syncBackgroundKeepAliveFlag();
            });
        }

        @JavascriptInterface
        public void setKeepScreenOn(final boolean keepOn) {
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (keepOn) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            });
        }

        @JavascriptInterface
        public void setBackIntercept(final boolean shouldIntercept) {
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                updateBackRegistration(shouldIntercept);
            });
        }

        @JavascriptInterface
        public void setStageTheme(final String themeName) {
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                applyStageTheme("light".equals(themeName));
            });
        }
    }

    @TargetApi(Build.VERSION_CODES.P)
    private static final class Api28Impl {
        static void enableShortEdgesCutout(Window window) {
            WindowManager.LayoutParams params = window.getAttributes();
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(params);
        }

        static int[] getCutoutSafeInsets(WindowInsets insets) {
            DisplayCutout cutout = insets.getDisplayCutout();
            if (cutout == null) return new int[]{0, 0, 0, 0};
            return new int[]{
                cutout.getSafeInsetLeft(),
                cutout.getSafeInsetTop(),
                cutout.getSafeInsetRight(),
                cutout.getSafeInsetBottom()
            };
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private static final class Api29Impl {
        static void disableSystemBarContrast(Window window) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
    }

    @TargetApi(Build.VERSION_CODES.R)
    private static final class Api30Impl {
        static Display getDisplay(Activity activity) {
            return activity.getDisplay();
        }

        static void disableContentCapture(View view) {
            view.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS);
        }

        static void configureFullBleedWindow(Window window) {
            window.setDecorFitsSystemWindows(false);
            WindowManager.LayoutParams params = window.getAttributes();
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            window.setAttributes(params);
        }

        static Insets getSafeInsets(WindowInsets insets) {
            return insets.getInsets(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
            );
        }

        static void setSystemBarsLightAppearance(Window window, boolean isLight) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(isLight ? mask : 0, mask);
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private static final class Api33Impl {
        static void disableAlgorithmicDarkening(WebSettings settings) {
            settings.setAlgorithmicDarkeningAllowed(false);
        }

        static Object createCallback(Runnable action) {
            return (OnBackInvokedCallback) action::run;
        }

        static void register(Activity activity, Object callbackObj) {
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                (OnBackInvokedCallback) callbackObj
            );
        }

        static void unregister(Activity activity, Object callbackObj) {
            activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                (OnBackInvokedCallback) callbackObj
            );
        }
    }

    @TargetApi(35)
    private static final class Api35Impl {
        static void requestHighFrameRate(Window window, WebView webView) {
            window.setFrameRatePowerSavingsBalanced(false);
            webView.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_HIGH);
        }
    }
}
