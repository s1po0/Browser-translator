package com.panel.reader;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
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
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {
    private static final int PICK_FILE = 44;
    private static final int MAX_IMAGES = 18;
    private static final int MAX_TEXT_BLOCKS = 48;
    private static final int MAX_IMAGE_BYTES = 16 * 1024 * 1024;
    private static final String BG = "#0D0D10";
    private static final String CARD = "#19181E";
    private static final String LILAC = "#B7A1FF";

    private final ExecutorService ocrExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService translationExecutor = Executors.newFixedThreadPool(4);
    private TextRecognizer latinRecognizer;
    private TextRecognizer koreanRecognizer;
    private LinearLayout root;
    private EditText addressBar;
    private TextView statusText;
    private Button translateButton;
    private ProgressBar pageProgress;
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private boolean hasTranslations = false;
    private boolean translationsVisible = true;
    private boolean translating = false;
    private boolean standaloneImagePicker = false;

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
        translateButton.setOnLongClickListener(v -> { hasTranslations = false; onTranslatePressed(); return true; });

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
                hasTranslations = false; translationsVisible = true; translating = false;
                translateButton.setText("وەرگێڕان");
                addressBar.setText(url);
                statusText.setText("دەکرێتەوە…");
            }
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                addressBar.setText(url);
                statusText.setText(view.getTitle() == null ? "ئامادەیە" : view.getTitle());
                pageProgress.setVisibility(View.GONE);
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
        upload.setOnClickListener(v -> { Intent intent = new Intent(Intent.ACTION_GET_CONTENT); intent.setType("image/*"); intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); standaloneImagePicker = true; startActivityForResult(Intent.createChooser(intent, "وێنەی مانگا هەڵبژێرە"), PICK_FILE); });
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

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FILE) return;
        if (fileCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            fileCallback.onReceiveValue(result); fileCallback = null;
            return;
        }
        if (!standaloneImagePicker) return;
        standaloneImagePicker = false;
        if (resultCode != RESULT_OK || data == null) return;
        ArrayList<Uri> images = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) images.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) images.add(data.getData());
        if (images.isEmpty()) return;
        StringBuilder html = new StringBuilder("<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>body{margin:0;background:#0d0d10}.page{padding:4px 0}.page img{display:block;width:100%;height:auto;margin:0 auto 4px}.n{color:#aaa;font:12px sans-serif;padding:8px}</style></head><body>");
        for (int i = 0; i < images.size(); i++) {
            Uri image = images.get(i);
            try { getContentResolver().takePersistableUriPermission(image, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
            html.append("<div class='page'><div class='n'>").append(i + 1).append(" / ").append(images.size()).append("</div><img src='")
                    .append(android.text.TextUtils.htmlEncode(image.toString())).append("' alt='Manhwa page ").append(i + 1).append("'></div>");
        }
        html.append("</body></html>");
        addressBar.setText("وێنەکانی هەڵبژێردراو");
        webView.loadDataWithBaseURL("https://panel.local/", html.toString(), "text/html", "UTF-8", null);
    }

    private void onTranslatePressed() {
        if (translating) return;
        if (hasTranslations) {
            translationsVisible = !translationsVisible;
            webView.evaluateJavascript("window.__panelSetVisible && window.__panelSetVisible(" + translationsVisible + ")", null);
            statusText.setText(translationsVisible ? "وەرگێڕانەکان پیشان دراون" : "وەرگێڕانەکان شاردراونەتەوە");
            return;
        }
        translating = true;
        translateButton.setEnabled(false);
        statusText.setText("وێنەکان دەگەڕێم بۆ دەق…");
        webView.evaluateJavascript("(()=>{let l=document.getElementById('panel-sorani-overlay-layer');if(l)l.remove();window.__panelOverlays=[];})()", null);
        String scan = "(()=>{let n=0;return Array.from(document.images).filter(i=>i.complete&&i.naturalWidth>140&&i.naturalHeight>70&&i.getBoundingClientRect().width>45).slice(0," + MAX_IMAGES + ").map(i=>{if(!i.dataset.panelBrowserId)i.dataset.panelBrowserId='panel-'+(++n)+'-'+Math.random().toString(36).slice(2,7);let r=i.getBoundingClientRect();return {id:i.dataset.panelBrowserId,src:i.currentSrc||i.src,left:r.left,top:r.top,width:r.width,height:r.height,naturalWidth:i.naturalWidth,naturalHeight:i.naturalHeight}})})()";
        webView.evaluateJavascript(scan, raw -> {
            try {
                JSONArray images = new JSONArray(raw == null || "null".equals(raw) ? "[]" : raw);
                if (images.length() == 0) { finishWithMessage("لە پەڕەکەدا وێنەی مانگای گونجاو نەدۆزرایەوە"); return; }
                ocrExecutor.execute(() -> translateImages(images));
            } catch (Exception e) { finishWithMessage("نەتوانرا وێنەکانی پەڕەکە بخوێندرێنەوە"); }
        });
    }

    private void translateImages(JSONArray images) {
        ArrayList<PendingOverlay> pending = new ArrayList<>();
        int imageCount = images.length();
        for (int i = 0; i < imageCount; i++) {
            if (pending.size() >= MAX_TEXT_BLOCKS) break;
            final int progress = i + 1;
            runOnUiThread(() -> statusText.setText("OCRی پانێڵ " + progress + " لە " + imageCount + "…"));
            try {
                JSONObject image = images.getJSONObject(i);
                Bitmap bitmap = downloadBitmap(image.getString("src"));
                if (bitmap == null) continue;
                InputImage input = InputImage.fromBitmap(bitmap, 0);
                Text latin = null, korean = null;
                try { latin = Tasks.await(latinRecognizer.process(input), 30, TimeUnit.SECONDS); } catch (Exception ignored) { }
                try { korean = Tasks.await(koreanRecognizer.process(input), 35, TimeUnit.SECONDS); } catch (Exception ignored) { }
                Text chosen = containsHangul(korean == null ? "" : korean.getText()) ? korean : latin;
                if (chosen != null) {
                    for (Text.TextBlock block : chosen.getTextBlocks()) {
                        if (pending.size() >= MAX_TEXT_BLOCKS) break;
                        Rect box = block.getBoundingBox();
                        String source = block.getText() == null ? "" : block.getText().trim();
                        if (box == null || source.length() < 2) continue;
                        Future<String> translated = translationExecutor.submit(() -> translateSorani(source));
                        pending.add(new PendingOverlay(image.getString("id"),
                                box.left / (float) bitmap.getWidth(), box.top / (float) bitmap.getHeight(),
                                box.width() / (float) bitmap.getWidth(), box.height() / (float) bitmap.getHeight(), translated));
                    }
                }
                bitmap.recycle();
            } catch (Exception ignored) { }
        }
        JSONArray overlays = new JSONArray();
        for (PendingOverlay item : pending) {
            try {
                String translated = item.translation.get(25, TimeUnit.SECONDS);
                if (translated == null || translated.trim().isEmpty()) continue;
                JSONObject overlay = new JSONObject();
                overlay.put("imageId", item.imageId); overlay.put("x", item.x); overlay.put("y", item.y);
                overlay.put("w", item.width); overlay.put("h", item.height); overlay.put("text", translated.trim());
                overlays.put(overlay);
            } catch (Exception ignored) { }
        }
        if (overlays.length() == 0) { finishWithMessage("دەقێک نەدۆزرایەوە یان وەرگێڕان بەردەست نەبوو"); return; }
        final String data = JSONObject.quote(overlays.toString());
        String script = "(()=>{try{" +
                "let layer=document.getElementById('panel-sorani-overlay-layer');" +
                "if(!layer){layer=document.createElement('div');layer.id='panel-sorani-overlay-layer';Object.assign(layer.style,{position:'fixed',inset:'0',zIndex:'2147483647',pointerEvents:'none'});document.documentElement.appendChild(layer);}" +
                "window.__panelOverlays=window.__panelOverlays||[];" +
                "window.__panelReposition=()=>{for(const b of window.__panelOverlays){const im=Array.from(document.images).find(x=>x.dataset.panelBrowserId===b.dataset.imageId);if(!im){b.style.display='none';continue;}const r=im.getBoundingClientRect();b.style.left=(r.left+Number(b.dataset.x)*r.width)+'px';b.style.top=(r.top+Number(b.dataset.y)*r.height)+'px';b.style.width=Math.max(78,Number(b.dataset.w)*r.width+18)+'px';b.style.minHeight=Math.max(38,Number(b.dataset.h)*r.height+12)+'px';}};"+
                "window.__panelSetVisible=(v)=>{layer.style.display=v?'block':'none';};"+
                "const items=JSON.parse(" + data + ");for(const item of items){const b=document.createElement('button');b.type='button';b.textContent=item.text;b.dataset.imageId=item.imageId;b.dataset.x=item.x;b.dataset.y=item.y;b.dataset.w=item.w;b.dataset.h=item.h;Object.assign(b.style,{position:'absolute',boxSizing:'border-box',display:'flex',alignItems:'center',justifyContent:'center',padding:'6px 10px',border:'1px solid rgba(30,20,40,.16)',borderRadius:'22px',background:'rgba(255,255,255,.96)',color:'#17131f',boxShadow:'0 2px 10px rgba(0,0,0,.32)',font:'600 14px/1.35 Noto Naskh Arabic,Noto Sans Arabic,sans-serif',direction:'rtl',textAlign:'center',whiteSpace:'normal',pointerEvents:'auto',cursor:'pointer',transition:'opacity .16s'});b.addEventListener('click',()=>{b.style.opacity=b.style.opacity==='0'?'1':'0'});layer.appendChild(b);window.__panelOverlays.push(b);}window.__panelReposition();if(!window.__panelListeners){window.addEventListener('scroll',window.__panelReposition,true);window.addEventListener('resize',window.__panelReposition);window.__panelListeners=true;}window.__panelSetVisible(true);return true;}catch(e){return false;}})()";
        runOnUiThread(() -> webView.evaluateJavascript(script, value -> {
            translating = false; translateButton.setEnabled(true);
            if (value != null && value.contains("true")) {
                hasTranslations = true; translationsVisible = true; translateButton.setText("پیشان بدە / بشارەوە");
                statusText.setText("" + overlays.length() + " دەق بە کوردیی سۆرانی وەرگێڕدرا");
            } else statusText.setText("نەتوانرا وەرگێڕانەکان لەسەر وێنەکە دابنرێن");
        }));
    }

    private Bitmap downloadBitmap(String address) throws Exception {
        Uri uri = Uri.parse(address);
        byte[] bytes;
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) return null;
                bytes = readLimited(input);
            }
        } else {
            URL url = new URL(address);
            if (!"http".equalsIgnoreCase(url.getProtocol()) && !"https".equalsIgnoreCase(url.getProtocol())) return null;
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(12000); connection.setReadTimeout(18000); connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", webView.getSettings().getUserAgentString());
            connection.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");
            String cookie = CookieManager.getInstance().getCookie(address);
            if (cookie != null) connection.setRequestProperty("Cookie", cookie);
            String referer = webView.getUrl(); if (referer != null) connection.setRequestProperty("Referer", referer);
            connection.connect();
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) { connection.disconnect(); return null; }
            int length = connection.getContentLength(); if (length > MAX_IMAGE_BYTES) { connection.disconnect(); return null; }
            try (InputStream input = connection.getInputStream()) { bytes = readLimited(input); }
            finally { connection.disconnect(); }
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1) return null;
        int sample = 1; while (bounds.outWidth / sample > 2400 || bounds.outHeight / sample > 2400) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private byte[] readLimited(InputStream input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int read, total = 0;
        while ((read = input.read(buffer)) != -1) { total += read; if (total > MAX_IMAGE_BYTES) throw new IllegalArgumentException("Image is too large"); out.write(buffer, 0, read); }
        return out.toByteArray();
    }

    private String translateSorani(String text) {
        HttpURLConnection connection = null;
        try {
            String query = URLEncoder.encode(text, StandardCharsets.UTF_8.name());
            URL url = new URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=ckb&dt=t&q=" + query);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10000); connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 PanelReader/1.0");
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n;
                while ((n = input.read(buf)) != -1) out.write(buf, 0, n);
                JSONArray response = new JSONArray(out.toString(StandardCharsets.UTF_8.name()));
                JSONArray chunks = response.optJSONArray(0); StringBuilder result = new StringBuilder();
                if (chunks != null) for (int i = 0; i < chunks.length(); i++) {
                    JSONArray chunk = chunks.optJSONArray(i);
                    if (chunk != null && chunk.optString(0) != null && !"null".equals(chunk.optString(0))) result.append(chunk.optString(0));
                }
                return result.toString().trim();
            }
        } catch (Exception e) { return null; }
        finally { if (connection != null) connection.disconnect(); }
    }

    private boolean containsHangul(String text) { if (text == null) return false; for (int i = 0; i < text.length(); i++) { char c = text.charAt(i); if (c >= 0xAC00 && c <= 0xD7AF) return true; } return false; }

    private void finishWithMessage(String message) {
        runOnUiThread(() -> { translating = false; translateButton.setEnabled(true); statusText.setText(message); });
    }

    private static class PendingOverlay {
        final String imageId; final float x, y, width, height; final Future<String> translation;
        PendingOverlay(String imageId, float x, float y, float width, float height, Future<String> translation) {
            this.imageId = imageId; this.x = x; this.y = y; this.width = width; this.height = height; this.translation = translation;
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (webView != null) { webView.stopLoading(); webView.destroy(); }
        if (latinRecognizer != null) latinRecognizer.close();
        if (koreanRecognizer != null) koreanRecognizer.close();
        ocrExecutor.shutdownNow(); translationExecutor.shutdownNow();
    }
}
