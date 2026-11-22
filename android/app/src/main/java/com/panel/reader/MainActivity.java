package com.panel.reader;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.ClipData;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {
    private static final int PICK_FILE = 44;
    private static final int CAPTURE_IMAGE = 45;
    private static final int MAX_IMAGES = 18;
    private static final int MAX_TEXT_BLOCKS = 48;
    private static final String BG = "#0D0D10";
    private static final String CARD = "#19181E";
    private static final String LILAC = "#B7A1FF";

    private final ExecutorService ocrExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService translationExecutor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<String, String> translationCache = new ConcurrentHashMap<>();
    private Runnable scrollScanRunnable;
    private TextRecognizer latinRecognizer;
    private TextRecognizer koreanRecognizer;
    private LinearLayout root;
    private EditText addressBar;
    private TextView statusText;
    private Button translateButton;
    private ProgressBar pageProgress;
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private boolean liveTranslationEnabled = false;
    private boolean translating = false;
    private boolean pendingViewportScan = false;
    private boolean standaloneImagePicker = false;
    private boolean autoTranslateNextPage = false;
    private Uri cameraPhotoUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(13, 13, 16));
        getWindow().setNavigationBarColor(Color.rgb(13, 13, 16));
        latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        koreanRecognizer = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
        buildBrowser();
        loadUrl("https://www.google.com");
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (webView != null && webView.canGoBack()) webView.goBack();
                else finish();
            }
        });
    }

    private void buildBrowser() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor(BG));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(12), dp(8), dp(12), dp(8));
        top.setBackgroundColor(Color.parseColor(BG));
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout addressRow = new LinearLayout(this);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = new TextView(this);
        mark.setText("▮▮▮");
        mark.setTextColor(Color.parseColor(LILAC));
        mark.setTextSize(14);
        mark.setTypeface(null, 1);
        mark.setPadding(0, 0, dp(9), 0);
        addressRow.addView(mark, new LinearLayout.LayoutParams(-2, -2));

        addressBar = new EditText(this);
        addressBar.setSingleLine(true);
        addressBar.setTextSize(14);
        addressBar.setTextColor(Color.WHITE);
        addressBar.setHintTextColor(Color.rgb(144, 141, 153));
        addressBar.setHint("پەڕە یان لینکی مانگا بنووسە");
        addressBar.setPadding(dp(12), 0, dp(8), 0);
        addressBar.setBackground(roundDrawable(Color.rgb(29, 28, 35), dp(11), Color.rgb(57, 54, 66)));
        addressBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressBar.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        addressRow.addView(addressBar, new LinearLayout.LayoutParams(0, dp(43), 1));
        Button go = makeButton("بڕۆ", true);
        LinearLayout.LayoutParams goParams = new LinearLayout.LayoutParams(-2, dp(43));
        goParams.leftMargin = dp(7);
        addressRow.addView(go, goParams);
        top.addView(addressRow);
        go.setOnClickListener(v -> navigateFromAddress());
        addressBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                navigateFromAddress(); return true;
            }
            return false;
        });

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(dp(2), dp(8), 0, 0);
        statusText = new TextView(this);
        statusText.setText("Panel Browser  ·  گەڕان و وەرگێڕانی مانگا");
        statusText.setTextColor(Color.rgb(151, 147, 163));
        statusText.setTextSize(11);
        statusRow.addView(statusText, new LinearLayout.LayoutParams(0, -2, 1));
        translateButton = makeButton("وەرگێڕان", true);
        statusRow.addView(translateButton, new LinearLayout.LayoutParams(-2, dp(36)));
        top.addView(statusRow);
        translateButton.setOnClickListener(v -> onTranslatePressed());
        translateButton.setOnLongClickListener(v -> { if (liveTranslationEnabled) { scanViewportNow(); return true; } return false; });

        pageProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pageProgress.setMax(100);
        pageProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor(LILAC)));
        pageProgress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(40, 38, 47)));
        pageProgress.setVisibility(View.GONE);
        top.addView(pageProgress, new LinearLayout.LayoutParams(-1, dp(2)));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        if (android.os.Build.VERSION.SDK_INT >= 26) settings.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                return !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                liveTranslationEnabled = false; translating = false; pendingViewportScan = false;
                if (scrollScanRunnable != null) mainHandler.removeCallbacks(scrollScanRunnable);
                translateButton.setText("وەرگێڕان");
                addressBar.setText(url);
                statusText.setText("دەکرێتەوە…");
            }
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                addressBar.setText(url);
                statusText.setText(view.getTitle() == null ? "ئامادەیە" : view.getTitle());
                pageProgress.setVisibility(View.GONE);
                if (autoTranslateNextPage) {
                    autoTranslateNextPage = false;
                    waitForSelectedImages(0);
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int progress) {
                if (progress < 100) { pageProgress.setVisibility(View.VISIBLE); pageProgress.setProgress(progress); }
                else pageProgress.setVisibility(View.GONE);
            }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = params.createIntent();
                try { startActivityForResult(intent, PICK_FILE); return true; }
                catch (Exception e) { fileCallback = null; Toast.makeText(MainActivity.this, "نەتوانرا فایلەکە بکرێتەوە", Toast.LENGTH_SHORT).show(); return false; }
            }
        });
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> downloadFile(url, userAgent, contentDisposition, mimeType));
        webView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> scheduleViewportScan());
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(dp(7), dp(7), dp(7), dp(7));
        controls.setBackgroundColor(Color.parseColor(CARD));
        Button back = makeButton("‹", false), forward = makeButton("›", false), reload = makeButton("⟳", false), home = makeButton("⌂", false), upload = makeButton("＋", false);
        addControl(controls, back); addControl(controls, forward); addControl(controls, reload); addControl(controls, home); addControl(controls, upload);
        back.setOnClickListener(v -> { if (webView.canGoBack()) webView.goBack(); });
        forward.setOnClickListener(v -> { if (webView.canGoForward()) webView.goForward(); });
        reload.setOnClickListener(v -> webView.reload());
        home.setOnClickListener(v -> loadUrl("https://www.google.com"));
        upload.setOnClickListener(v -> openImageSourcePicker());
        root.addView(controls, new LinearLayout.LayoutParams(-1, dp(57)));
        setContentView(root);
    }

    private void addControl(LinearLayout row, Button button) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(42), 1);
        p.leftMargin = dp(3); p.rightMargin = dp(3);
        row.addView(button, p);
    }

    private Button makeButton(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(primary ? 12 : 20);
        button.setTextColor(primary ? Color.rgb(26, 20, 36) : Color.rgb(224, 219, 239));
        button.setAllCaps(false);
        button.setMinWidth(0); button.setMinimumWidth(0);
        button.setPadding(dp(primary ? 12 : 4), 0, dp(primary ? 12 : 4), 0);
        button.setBackground(roundDrawable(primary ? Color.parseColor(LILAC) : Color.rgb(38, 36, 45), dp(10), primary ? Color.parseColor(LILAC) : Color.rgb(54, 51, 61)));
        return button;
    }

    private android.graphics.drawable.GradientDrawable roundDrawable(int color, int radius, int stroke) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(color); shape.setCornerRadius(radius); shape.setStroke(dp(1), stroke);
        return shape;
    }

    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private void navigateFromAddress() {
        String value = addressBar.getText().toString().trim();
        if (value.isEmpty()) return;
        String target;
        if (value.matches("(?i)^https?://.*")) target = value;
        else if (value.matches("(?i)^[a-z0-9.-]+\\.[a-z]{2,}([/:?#].*)?$")) target = "https://" + value;
        else target = "https://www.google.com/search?q=" + Uri.encode(value);
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(addressBar.getWindowToken(), 0);
        webView.loadUrl(target);
    }

    private void loadUrl(String url) { if (webView != null) webView.loadUrl(url); }

    private void downloadFile(String url, String userAgent, String disposition, String mimeType) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setMimeType(mimeType);
            request.addRequestHeader("User-Agent", userAgent);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) request.addRequestHeader("Cookie", cookie);
            request.setTitle(android.webkit.URLUtil.guessFileName(url, disposition, mimeType));
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, android.webkit.URLUtil.guessFileName(url, disposition, mimeType));
            ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
            Toast.makeText(this, "داگرتن دەستی پێکرد", Toast.LENGTH_SHORT).show();
        } catch (Exception e) { Toast.makeText(this, "داگرتن سەرکەوتوو نەبوو", Toast.LENGTH_SHORT).show(); }
    }

    private void waitForSelectedImages(int attempt) {
        webView.evaluateJavascript("Array.from(document.images).length>0&&Array.from(document.images).every(i=>i.complete&&i.naturalWidth>0)", ready -> {
            if ("true".equals(ready) || attempt >= 20) onTranslatePressed();
            else webView.postDelayed(() -> waitForSelectedImages(attempt + 1), 250);
        });
    }

    private void openImageSourcePicker() {
        String[] choices = {"وێنە بگرە", "لە گەلەری هەڵبژێرە"};
        new AlertDialog.Builder(this).setTitle("وەرگێڕانی مانگا").setItems(choices, (dialog, which) -> {
            if (which == 0) captureManhwaPhoto(); else pickManhwaImages();
        }).show();
    }

    private void pickManhwaImages() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*"); intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        standaloneImagePicker = true;
        startActivityForResult(Intent.createChooser(intent, "وێنەی مانگا هەڵبژێرە"), PICK_FILE);
    }

    private void captureManhwaPhoto() {
        Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (camera.resolveActivity(getPackageManager()) == null) {
            Toast.makeText(this, "کامێرا بەردەست نییە", Toast.LENGTH_SHORT).show(); return;
        }
        try {
            File photo = new File(getCacheDir(), "panel-capture-" + System.currentTimeMillis() + ".jpg");
            cameraPhotoUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photo);
            camera.putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri);
            camera.setClipData(ClipData.newUri(getContentResolver(), "Manhwa photo", cameraPhotoUri));
            camera.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(camera, CAPTURE_IMAGE);
        } catch (Exception e) { Toast.makeText(this, "نەتوانرا کامێرا بکرێتەوە", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CAPTURE_IMAGE) {
            if (resultCode == RESULT_OK && cameraPhotoUri != null) {
                ArrayList<Uri> photos = new ArrayList<>(); photos.add(cameraPhotoUri); showImagePages(photos);
            } else statusText.setText("وێنە نەگیرا");
            cameraPhotoUri = null; return;
        }
        if (requestCode != PICK_FILE) return;
        if (fileCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            fileCallback.onReceiveValue(result); fileCallback = null; return;
        }
        if (!standaloneImagePicker) return;
        standaloneImagePicker = false;
        if (resultCode != RESULT_OK || data == null) return;
        ArrayList<Uri> images = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) images.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) images.add(data.getData());
        if (!images.isEmpty()) showImagePages(images);
    }

    private void showImagePages(ArrayList<Uri> images) {
        StringBuilder html = new StringBuilder("<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>body{margin:0;background:#0d0d10}.page{padding:4px 0}.page img{display:block;width:100%;height:auto;margin:0 auto 4px}.n{color:#aaa;font:12px sans-serif;padding:8px}</style></head><body>");
        for (int i = 0; i < images.size(); i++) {
            Uri image = images.get(i);
            html.append("<div class='page'><div class='n'>").append(i + 1).append(" / ").append(images.size()).append("</div><img src='")
                    .append(android.text.TextUtils.htmlEncode(image.toString())).append("' alt='Manhwa page ").append(i + 1).append("'></div>");
        }
        html.append("</body></html>");
        addressBar.setText("وێنەکانی هەڵبژێردراو");
        autoTranslateNextPage = true;
        webView.loadDataWithBaseURL("https://panel.local/", html.toString(), "text/html", "UTF-8", null);
    }

    private void onTranslatePressed() {
        if (liveTranslationEnabled) {
            liveTranslationEnabled = false;
            pendingViewportScan = false;
            if (scrollScanRunnable != null) mainHandler.removeCallbacks(scrollScanRunnable);
            webView.evaluateJavascript("(()=>{let l=document.getElementById('panel-sorani-overlay-layer');if(l)l.remove();window.__panelSetVisible=null;})()", null);
            translateButton.setText("وەرگێڕان");
            statusText.setText("وەرگێڕانی لایڤ وەستا");
            return;
        }
        if (translating) { pendingViewportScan = true; return; }
        liveTranslationEnabled = true;
        translateButton.setText("Live · سۆرانی");
        statusText.setText("وەرگێڕانی لایڤ چالاکە · وێنەکان دەخوێنمەوە…");
        scanViewportNow();
    }

    private void scheduleViewportScan() {
        if (!liveTranslationEnabled) return;
        if (translating) { pendingViewportScan = true; return; }
        if (scrollScanRunnable != null) mainHandler.removeCallbacks(scrollScanRunnable);
        scrollScanRunnable = this::scanViewportNow;
        mainHandler.postDelayed(scrollScanRunnable, 550);
    }

    private void scanViewportNow() {
        if (!liveTranslationEnabled || webView == null) return;
        if (translating) { pendingViewportScan = true; return; }
        translating = true;
        pendingViewportScan = false;
        statusText.setText("وێنەکانی شاشەکە دەخوێنمەوە…");
        String infoScript = "(()=>{const r=[];const add=i=>{if(r.length>=" + MAX_IMAGES + ")return;const b=i.getBoundingClientRect();if(b.width<35||b.height<24||b.bottom<=0||b.top>=innerHeight||b.right<=0||b.left>=innerWidth)return;r.push({left:b.left,top:b.top,right:b.right,bottom:b.bottom});};for(const i of Array.from(document.images)){if(i.complete&&i.naturalWidth>=40)add(i);}for(const e of document.querySelectorAll('canvas'))add(e);for(const e of document.querySelectorAll('div,section,figure')){if(getComputedStyle(e).backgroundImage!=='none')add(e);}return {width:innerWidth,height:innerHeight,images:r};})()";
        webView.evaluateJavascript("(()=>{let l=document.getElementById('panel-sorani-overlay-layer');if(l)l.remove();})()", removed ->
                webView.evaluateJavascript(infoScript, raw -> {
                    try {
                        JSONObject info = new JSONObject(raw == null ? "{}" : raw);
                        JSONArray images = info.optJSONArray("images");
                        if (images == null || images.length() == 0) { finishViewportScan("ئەم بەشەی پەڕە وێنەی لۆدکراوی تێدا نییە"); return; }
                        webView.post(() -> {
                            try {
                                int width = webView.getWidth(), height = webView.getHeight();
                                if (width < 2 || height < 2) { finishViewportScan("شاشە ئامادە نییە"); return; }
                                Bitmap screenshot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                                webView.draw(new Canvas(screenshot));
                                ocrExecutor.execute(() -> translateViewportScreenshot(screenshot, images, info.optDouble("width", width), info.optDouble("height", height)));
                            } catch (Exception e) { finishViewportScan("نەتوانرا وێنەی شاشەکە پشکنرێت"); }
                        });
                    } catch (Exception e) { finishViewportScan("نەتوانرا وێنەکانی شاشەکە بناسرێن"); }
                }));
    }

    private void translateViewportScreenshot(Bitmap screenshot, JSONArray imageRects, double cssWidth, double cssHeight) {
        try {
            float scaleX = screenshot.getWidth() / (float) Math.max(1, cssWidth);
            float scaleY = screenshot.getHeight() / (float) Math.max(1, cssHeight);
            InputImage input = InputImage.fromBitmap(screenshot, 0);
            Text latin = null, korean = null;
            try { latin = Tasks.await(latinRecognizer.process(input), 25, TimeUnit.SECONDS); } catch (Exception ignored) { }
            try { korean = Tasks.await(koreanRecognizer.process(input), 30, TimeUnit.SECONDS); } catch (Exception ignored) { }
            Text recognized = containsHangul(korean == null ? "" : korean.getText()) ? korean : latin;
            if (recognized == null) { screenshot.recycle(); finishViewportScan("دەقێکی خوێندراوە لە وێنەکاندا نەدۆزرایەوە"); return; }

            ArrayList<ViewportTranslation> pending = new ArrayList<>();
            Set<String> unique = new HashSet<>();
            for (Text.TextBlock block : recognized.getTextBlocks()) {
                if (pending.size() >= MAX_TEXT_BLOCKS) break;
                Rect box = block.getBoundingBox();
                String source = block.getText() == null ? "" : block.getText().trim();
                if (box == null || source.length() < 2 || !unique.add(source)) continue;
                double cx = box.exactCenterX() / scaleX, cy = box.exactCenterY() / scaleY;
                boolean overImage = false;
                for (int i = 0; i < imageRects.length(); i++) {
                    JSONObject image = imageRects.optJSONObject(i); if (image == null) continue;
                    if (cx >= image.optDouble("left") && cx <= image.optDouble("right") && cy >= image.optDouble("top") && cy <= image.optDouble("bottom")) { overImage = true; break; }
                }
                if (!overImage) continue;
                Future<String> translated = translationExecutor.submit(() -> translateSorani(source));
                pending.add(new ViewportTranslation(box.left / scaleX, box.top / scaleY, box.width() / scaleX, box.height() / scaleY, translated));
            }
            screenshot.recycle();
            if (pending.isEmpty()) { finishViewportScan("دەقی قسە لە وێنەی دەرکەوتوو نەدۆزرایەوە"); return; }

            JSONArray overlays = new JSONArray();
            for (ViewportTranslation item : pending) {
                try {
                    String translated = item.translation.get(20, TimeUnit.SECONDS);
                    if (translated == null || translated.trim().isEmpty()) continue;
                    JSONObject overlay = new JSONObject();
                    overlay.put("x", item.x); overlay.put("y", item.y); overlay.put("w", item.width); overlay.put("h", item.height); overlay.put("text", translated.trim());
                    overlays.put(overlay);
                } catch (Exception ignored) { }
            }
            if (overlays.length() == 0) { finishViewportScan("OCR دەقەکەی دۆزییەوە، بەڵام وەرگێڕان وەڵامی نەدا"); return; }
            String payload = JSONObject.quote(overlays.toString());
            String script = "(()=>{try{let l=document.createElement('div');l.id='panel-sorani-overlay-layer';Object.assign(l.style,{position:'fixed',inset:'0',zIndex:'2147483647',pointerEvents:'none'});document.documentElement.appendChild(l);window.__panelSetVisible=(v)=>{for(const e of l.querySelectorAll('.panel-translation'))e.style.display=v?'flex':'none';};const a=JSON.parse(" + payload + ");for(const q of a){const b=document.createElement('button');b.className='panel-translation';b.type='button';b.textContent=q.text;Object.assign(b.style,{position:'fixed',left:Math.max(2,Math.min(q.x,innerWidth-80))+'px',top:Math.max(2,Math.min(q.y,innerHeight-36))+'px',width:Math.max(76,Math.min(q.w+18,innerWidth-8))+'px',minHeight:Math.max(36,Math.min(q.h+12,innerHeight))+'px',boxSizing:'border-box',display:'flex',alignItems:'center',justifyContent:'center',padding:'5px 9px',border:'1px solid rgba(30,20,40,.17)',borderRadius:'24px',background:'rgba(255,255,255,.97)',color:'#17131f',boxShadow:'0 2px 10px rgba(0,0,0,.3)',font:'600 14px/1.35 Noto Naskh Arabic,Noto Sans Arabic,sans-serif',direction:'rtl',textAlign:'center',whiteSpace:'normal',pointerEvents:'auto',cursor:'pointer'});b.addEventListener('click',()=>{b.style.opacity=b.style.opacity==='0'?'1':'0'});l.appendChild(b);}const c=document.createElement('button');c.textContent='سۆرانی ✓';Object.assign(c.style,{position:'fixed',right:'12px',bottom:'12px',zIndex:'2147483647',border:'1px solid #9c87ce',borderRadius:'18px',padding:'8px 13px',background:'#211b2d',color:'#efe8ff',font:'600 12px sans-serif',pointerEvents:'auto'});c.addEventListener('click',()=>{const hidden=c.dataset.hidden==='1';c.dataset.hidden=hidden?'0':'1';c.textContent=hidden?'سۆرانی ✓':'سۆرانی';window.__panelSetVisible(hidden);});l.appendChild(c);return true;}catch(e){return false;}})()";
            runOnUiThread(() -> {
                if (!liveTranslationEnabled) { finishViewportScan("وەرگێڕانی لایڤ وەستا"); return; }
                webView.evaluateJavascript(script, result -> {
                    if (result != null && result.contains("true")) finishViewportScan(overlays.length() + " دەق لە وێنەکانی شاشە وەرگێڕان");
                    else finishViewportScan("نەتوانرا وەرگێڕانەکان لەسەر وێنەکە دابنرێن");
                });
            });
        } catch (Exception e) { screenshot.recycle(); finishViewportScan("پشکنینی OCR سەرکەوتوو نەبوو"); }
    }

    private void finishViewportScan(String message) {
        runOnUiThread(() -> {
            translating = false;
            statusText.setText(message);
            if (pendingViewportScan && liveTranslationEnabled) {
                pendingViewportScan = false;
                mainHandler.postDelayed(this::scanViewportNow, 180);
            }
        });
    }

    private static class ViewportTranslation {
        final double x, y, width, height;
        final Future<String> translation;
        ViewportTranslation(double x, double y, double width, double height, Future<String> translation) {
            this.x = x; this.y = y; this.width = width; this.height = height; this.translation = translation;
        }
    }

    private String translateSorani(String text) {
        String cached = translationCache.get(text);
        if (cached != null) return cached;
        HttpURLConnection connection = null;
        try {
            String query = URLEncoder.encode(text, StandardCharsets.UTF_8.name());
            URL url = new URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=ckb&dt=t&q=" + query);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10000); connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 PanelBrowser/1.0");
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n;
                while ((n = input.read(buf)) != -1) out.write(buf, 0, n);
                JSONArray response = new JSONArray(out.toString(StandardCharsets.UTF_8.name()));
                JSONArray chunks = response.optJSONArray(0); StringBuilder result = new StringBuilder();
                if (chunks != null) for (int i = 0; i < chunks.length(); i++) {
                    JSONArray chunk = chunks.optJSONArray(i);
                    if (chunk != null && chunk.optString(0) != null && !"null".equals(chunk.optString(0))) result.append(chunk.optString(0));
                }
                String translated = result.toString().trim();
                if (!translated.isEmpty()) translationCache.put(text, translated);
                return translated;
            }
        } catch (Exception e) { return null; }
        finally { if (connection != null) connection.disconnect(); }
    }

    private boolean containsHangul(String text) { if (text == null) return false; for (int i = 0; i < text.length(); i++) { char c = text.charAt(i); if (c >= 0xAC00 && c <= 0xD7AF) return true; } return false; }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (webView != null) { webView.stopLoading(); webView.destroy(); }
        if (latinRecognizer != null) latinRecognizer.close();
        if (koreanRecognizer != null) koreanRecognizer.close();
        ocrExecutor.shutdownNow(); translationExecutor.shutdownNow();
    }
}
