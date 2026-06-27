package top.sisbilhar.app;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.Toast;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;
import com.getcapacitor.BridgeWebChromeClient;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends BridgeActivity {

    private static final int REQUEST_BT_PERMISSIONS = 1001;
    private static final int INPUT_FILE_REQUEST_CODE = 12345;
    private byte[] pendingPrintBytes = null;
    private boolean isSettingsBtnAdded = false;
    private ValueCallback<Uri[]> mUploadMessage = null;
    private Uri mCameraPhotoUri = null;
    private Bundle savedInstanceStateBundle = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.savedInstanceStateBundle = savedInstanceState;
    }

    @Override
    public void onStart() {
        super.onStart();
        
        // 1. Get WebView and configure custom client to intercept URLs and handle custom WebChromeClient
        final WebView webView = this.bridge.getWebView();
        if (webView != null) {
            // Add Share Bridge Interface to support Web Share API natively
            webView.addJavascriptInterface(new Object() {
                @android.webkit.JavascriptInterface
                public void shareFile(String base64Data, String fileName, String title, String text) {
                    try {
                        byte[] bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT);
                        java.io.File shareFile = new java.io.File(getCacheDir(), fileName);
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(shareFile);
                        fos.write(bytes);
                        fos.flush();
                        fos.close();

                        Uri contentUri = androidx.core.content.FileProvider.getUriForFile(MainActivity.this,
                                getPackageName() + ".fileprovider",
                                shareFile);

                        Intent intent = new Intent(Intent.ACTION_SEND);
                        intent.setType("image/jpeg");
                        intent.putExtra(Intent.EXTRA_STREAM, contentUri);
                        intent.putExtra(Intent.EXTRA_SUBJECT, title);
                        intent.putExtra(Intent.EXTRA_TEXT, text);
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                        startActivity(Intent.createChooser(intent, "Compartilhar Recibo"));
                    } catch (Exception e) {
                        e.printStackTrace();
                        showToast("Erro ao compartilhar recibo: " + e.getMessage());
                    }
                }
            }, "AndroidShareBridge");

            webView.setWebViewClient(new BridgeWebViewClient(this.bridge) {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    String url = request.getUrl().toString();
                    if (handleUrlLoading(view, url)) {
                        return true;
                    }
                    return super.shouldOverrideUrlLoading(view, request);
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    if (handleUrlLoading(view, url)) {
                        return true;
                    }
                    return super.shouldOverrideUrlLoading(view, url);
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    // Inject Web Share Polyfill whenever a page finishes loading
                    injectSharePolyfill(view);
                }
            });

            // Handle Camera Only uploads (forces Camera for all image inputs to prevent falsification)
            webView.setWebChromeClient(new BridgeWebChromeClient(this.bridge) {
                @Override
                public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                    boolean isImage = false;
                    for (String type : fileChooserParams.getAcceptTypes()) {
                        if (type.contains("image")) {
                            isImage = true;
                            break;
                        }
                    }

                    if (isImage) {
                        if (mUploadMessage != null) {
                            mUploadMessage.onReceiveValue(null);
                        }
                        mUploadMessage = filePathCallback;

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                                requestPermissions(new String[]{Manifest.permission.CAMERA}, 1002);
                                return true;
                            }
                        }
                        openInAppCamera();
                        return true;
                    }
                    return super.onShowFileChooser(webView, filePathCallback, fileChooserParams);
                }
            });

            // 2. Fast Launch Redirect: If company slug is saved, load it (or restore state if recreated)
            if (this.savedInstanceStateBundle != null) {
                webView.restoreState(this.savedInstanceStateBundle);
                String uriStr = this.savedInstanceStateBundle.getString("camera_photo_uri", null);
                if (uriStr != null) {
                    mCameraPhotoUri = Uri.parse(uriStr);
                }
            } else {
                SharedPreferences prefs = getSharedPreferences("SisBilharPrefs", MODE_PRIVATE);
                String slug = prefs.getString("company_slug", null);
                if (slug != null && !slug.trim().isEmpty()) {
                    webView.loadUrl("https://sisbilhar.top/" + slug.trim());
                }
            }
        }

        // 3. Programmatically inject the floating settings button
        if (!isSettingsBtnAdded) {
            addFloatingSettingsButton();
        }
    }

    /**
     * Intercepts custom schemes and RawBT printing intents.
     */
    private boolean handleUrlLoading(WebView view, String url) {
        if (url.startsWith("intent:")) {
            if (url.contains("package=ru.a402d.rawbtprinter")) {
                handleRawBTPrintIntent(url);
                return true;
            }
        } else if (url.startsWith("setcompany://")) {
            String slug = url.substring("setcompany://".length());
            // Clean slug to prevent path injection
            slug = slug.replaceAll("[^a-zA-Z0-9-_]", "");
            
            SharedPreferences.Editor editor = getSharedPreferences("SisBilharPrefs", MODE_PRIVATE).edit();
            editor.putString("company_slug", slug);
            editor.apply();
            
            showToast("Acesso da empresa configurado!");
            view.loadUrl("https://sisbilhar.top/" + slug);
            return true;
        }
        return false;
    }

    private void injectSharePolyfill(WebView view) {
        String js = "(function() {\n" +
                "    if (window.AndroidShareBridge) {\n" +
                "        navigator.share = function(data) {\n" +
                "            return new Promise(function(resolve, reject) {\n" +
                "                try {\n" +
                "                    if (data && data.files && data.files.length > 0) {\n" +
                "                        var file = data.files[0];\n" +
                "                        var reader = new FileReader();\n" +
                "                        reader.onloadend = function() {\n" +
                "                            var base64Data = reader.result.split(',')[1];\n" +
                "                            window.AndroidShareBridge.shareFile(\n" +
                "                                base64Data,\n" +
                "                                file.name,\n" +
                "                                data.title || 'Compartilhar',\n" +
                "                                data.text || ''\n" +
                "                            );\n" +
                "                            resolve();\n" +
                "                        };\n" +
                "                        reader.onerror = function(e) {\n" +
                "                            reject(new Error('Erro ao ler arquivo: ' + e.message));\n" +
                "                        };\n" +
                "                        reader.readAsDataURL(file);\n" +
                "                    } else {\n" +
                "                        reject(new Error('Nenhum arquivo fornecido para compartilhar.'));\n" +
                "                    }\n" +
                "                } catch(e) {\n" +
                "                    reject(e);\n" +
                "                }\n" +
                "            });\n" +
                "        };\n" +
                "        navigator.canShare = function(data) {\n" +
                "            return true;\n" +
                "        };\n" +
                "    }\n" +
                "})();";
        view.evaluateJavascript(js, null);
    }

    /**
     * Extracts and decodes percent-encoded raw ESC/POS bytes from RawBT intent.
     */
    private void handleRawBTPrintIntent(String url) {
        try {
            int startIndex = "intent:".length();
            int endIndex = url.indexOf("#Intent;");
            if (endIndex == -1) endIndex = url.length();
            
            String encodedText = url.substring(startIndex, endIndex);
            byte[] printBytes = decodePercentEncoding(encodedText);
            
            printDirectly(printBytes);
        } catch (Exception e) {
            e.printStackTrace();
            showToast("Erro ao decodificar impressão: " + e.getMessage());
        }
    }

    /**
     * Safe decoding of percent-encoded binary sequences directly to a raw byte array.
     */
    private byte[] decodePercentEncoding(String input) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '%') {
                if (i + 2 < input.length()) {
                    String hex = input.substring(i + 1, i + 3);
                    try {
                        int b = Integer.parseInt(hex, 16);
                        out.write(b);
                    } catch (NumberFormatException ignored) {
                        out.write('%');
                        out.write(input.charAt(i + 1));
                        out.write(input.charAt(i + 2));
                    }
                    i += 2;
                } else {
                    out.write('%');
                }
            } else if (c == '+') {
                out.write(' ');
            } else {
                out.write(c);
            }
        }
        return out.toByteArray();
    }

    /**
     * Establishes connection to the configured Bluetooth printer and prints.
     */
    private void printDirectly(final byte[] bytes) {
        SharedPreferences prefs = getSharedPreferences("SisBilharPrefs", MODE_PRIVATE);
        final String printerMac = prefs.getString("printer_mac", null);
        
        if (printerMac == null || printerMac.isEmpty()) {
            this.pendingPrintBytes = bytes;
            showPrinterSelectorDialog();
            return;
        }
        
        // Request Bluetooth permissions dynamically for Android 12+ (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                this.pendingPrintBytes = bytes;
                requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                }, REQUEST_BT_PERMISSIONS);
                return;
            }
        } else {
            // Android 10 and below require Location permissions to scan/connect Bluetooth
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                this.pendingPrintBytes = bytes;
                requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                }, REQUEST_BT_PERMISSIONS);
                return;
            }
        }
        
        showToast("Imprimindo recibo...");
        
        new Thread(new Runnable() {
            @Override
            public void run() {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null) {
                    runOnUiThread(() -> showToast("Bluetooth não suportado neste aparelho."));
                    return;
                }
                
                if (!adapter.isEnabled()) {
                    runOnUiThread(() -> showToast("Por favor, ligue o Bluetooth."));
                    return;
                }
                
                BluetoothSocket socket = null;
                try {
                    BluetoothDevice device = adapter.getRemoteDevice(printerMac);
                    // Standard SPP UUID for Bluetooth Serial communication
                    UUID sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
                    socket = device.createRfcommSocketToServiceRecord(sppUuid);
                    socket.connect();
                    
                    OutputStream out = socket.getOutputStream();
                    out.write(bytes);
                    out.flush();
                    
                    // Buffer sleep to ensure delivery
                    Thread.sleep(600);
                    
                    socket.close();
                    runOnUiThread(() -> showToast("Recibo impresso!"));
                } catch (Exception e) {
                    e.printStackTrace();
                    runOnUiThread(() -> {
                        showToast("Falha de conexão: " + e.getLocalizedMessage());
                        // Let user change printer if it failed
                        showPrinterSelectorDialog();
                    });
                } finally {
                    if (socket != null) {
                        try { 
                            socket.close(); 
                        } catch (Exception ignored) {
                            System.err.println("Erro ao fechar socket: " + ignored.getMessage());
                        }
                    }
                }
            }
        }).start();
    }

    /**
     * Dialog listing paired Bluetooth devices for thermal printer selection.
     */
    private void showPrinterSelectorDialog() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            showToast("Bluetooth não disponível.");
            return;
        }
        
        if (!adapter.isEnabled()) {
            showToast("Ative o Bluetooth para ver os aparelhos pareados.");
            return;
        }
        
        // Check BT permissions before listing devices
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BT_PERMISSIONS);
                return;
            }
        }
        
        Set<BluetoothDevice> pairedDevices = adapter.getBondedDevices();
        final List<BluetoothDevice> deviceList = new ArrayList<>(pairedDevices);
        
        if (deviceList.isEmpty()) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Sem aparelhos pareados");
            builder.setMessage("Pareie sua impressora térmica nas configurações de Bluetooth do celular e tente de novo.");
            builder.setPositiveButton("Abrir Bluetooth", (dialog, which) -> {
                startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
            });
            builder.setNegativeButton("Cancelar", null);
            builder.show();
            return;
        }
        
        String[] deviceNames = new String[deviceList.size()];
        for (int i = 0; i < deviceList.size(); i++) {
            BluetoothDevice d = deviceList.get(i);
            deviceNames[i] = d.getName() + " (" + d.getAddress() + ")";
        }
        
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Selecione a Impressora Térmica");
        builder.setItems(deviceNames, (dialog, which) -> {
            BluetoothDevice selectedDevice = deviceList.get(which);
            
            SharedPreferences.Editor editor = getSharedPreferences("SisBilharPrefs", MODE_PRIVATE).edit();
            editor.putString("printer_mac", selectedDevice.getAddress());
            editor.putString("printer_name", selectedDevice.getName());
            editor.apply();
            
            showToast("Impressora definida: " + selectedDevice.getName());
            
            if (this.pendingPrintBytes != null) {
                printDirectly(this.pendingPrintBytes);
                this.pendingPrintBytes = null;
            }
        });
        builder.setNegativeButton("Cancelar", null);
        builder.show();
    }

    /**
     * Programmatically overlay a small transparent floating gear button for settings.
     */
    private void addFloatingSettingsButton() {
        FrameLayout rootLayout = findViewById(android.R.id.content);
        if (rootLayout == null) return;
        
        ImageButton settingsBtn = new ImageButton(this);
        // Use default Android gear icon
        settingsBtn.setImageResource(android.R.drawable.ic_menu_preferences);
        settingsBtn.setBackgroundColor(Color.TRANSPARENT);
        settingsBtn.setAlpha(0.25f); // Low opacity to keep it non-intrusive
        
        int size = (int) (44 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size);
        params.gravity = Gravity.BOTTOM | Gravity.END;
        
        int margin = (int) (12 * getResources().getDisplayMetrics().density);
        params.setMargins(0, 0, margin, margin);
        settingsBtn.setLayoutParams(params);
        
        settingsBtn.setOnClickListener(v -> showAppSettingsMenu());
        
        rootLayout.addView(settingsBtn);
        isSettingsBtnAdded = true;
    }

    /**
     * App configuration dialog options.
     */
    private void showAppSettingsMenu() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Configurações SisBilhar");
        
        String[] options = {
            "Alterar Empresa (Código)",
            "Selecionar Impressora Bluetooth",
            "Resetar Tudo",
            "Fechar"
        };
        
        builder.setItems(options, (dialog, which) -> {
            if (which == 0) {
                clearCompanyAndRestart();
            } else if (which == 1) {
                showPrinterSelectorDialog();
            } else if (which == 2) {
                clearAllAndRestart();
            }
        });
        builder.show();
    }

    private void clearCompanyAndRestart() {
        SharedPreferences.Editor editor = getSharedPreferences("SisBilharPrefs", MODE_PRIVATE).edit();
        editor.remove("company_slug");
        editor.apply();
        
        showToast("Empresa limpa! Carregando configuração...");
        
        WebView webView = this.bridge.getWebView();
        if (webView != null) {
            webView.loadUrl("http://localhost/");
        }
    }

    private void clearAllAndRestart() {
        SharedPreferences.Editor editor = getSharedPreferences("SisBilharPrefs", MODE_PRIVATE).edit();
        editor.clear();
        editor.apply();
        
        showToast("Configurações resetadas!");
        
        WebView webView = this.bridge.getWebView();
        if (webView != null) {
            webView.loadUrl("http://localhost/");
        }
    }

    private void openInAppCamera() {
        final Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        
        FrameLayout rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.BLACK);
        
        final SurfaceView surfaceView = new SurfaceView(this);
        rootLayout.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
                
        final android.widget.Button btnCapture = new android.widget.Button(this);
        btnCapture.setText("Tirar Foto");
        btnCapture.setBackgroundColor(Color.parseColor("#3b82f6"));
        btnCapture.setTextColor(Color.WHITE);
        btnCapture.setTextSize(18);
        btnCapture.setPadding(30, 20, 30, 20);
        
        FrameLayout.LayoutParams btnParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        btnParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        btnParams.bottomMargin = (int) (70 * getResources().getDisplayMetrics().density);
        rootLayout.addView(btnCapture, btnParams);
        
        final android.widget.Button btnCancel = new android.widget.Button(this);
        btnCancel.setText("X");
        btnCancel.setBackgroundColor(Color.parseColor("#ef4444"));
        btnCancel.setTextColor(Color.WHITE);
        btnCancel.setTextSize(16);
        btnCancel.setPadding(10, 10, 10, 10);
        
        FrameLayout.LayoutParams cancelParams = new FrameLayout.LayoutParams(120, 120);
        cancelParams.gravity = Gravity.TOP | Gravity.START;
        cancelParams.leftMargin = 40;
        cancelParams.topMargin = 40;
        rootLayout.addView(btnCancel, cancelParams);
        
        dialog.setContentView(rootLayout);
        
        final android.hardware.Camera[] camera = {null};
        final int[] cameraRotation = {90};
        
        SurfaceHolder holder = surfaceView.getHolder();
        holder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                try {
                    camera[0] = android.hardware.Camera.open();
                    camera[0].setPreviewDisplay(holder);
                    
                    android.hardware.Camera.CameraInfo info = new android.hardware.Camera.CameraInfo();
                    android.hardware.Camera.getCameraInfo(0, info);
                    int rotation = getWindowManager().getDefaultDisplay().getRotation();
                    int degrees = 0;
                    switch (rotation) {
                        case android.view.Surface.ROTATION_0: degrees = 0; break;
                        case android.view.Surface.ROTATION_90: degrees = 90; break;
                        case android.view.Surface.ROTATION_180: degrees = 180; break;
                        case android.view.Surface.ROTATION_270: degrees = 270; break;
                    }
                    int result;
                    if (info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT) {
                        result = (info.orientation + degrees) % 360;
                        result = (360 - result) % 360;
                    } else {
                        result = (info.orientation - degrees + 360) % 360;
                    }
                    camera[0].setDisplayOrientation(result);
                    cameraRotation[0] = result;
                    
                    android.hardware.Camera.Parameters params = camera[0].getParameters();
                    
                    // Choose optimal picture size (closest to 1280x720 to keep memory low and upload fast)
                    android.hardware.Camera.Size optimalPicSize = getOptimalPictureSize(params.getSupportedPictureSizes(), 1280, 720);
                    if (optimalPicSize != null) {
                        params.setPictureSize(optimalPicSize.width, optimalPicSize.height);
                    }
                    
                    // Choose optimal preview size matching SurfaceView dimensions
                    android.hardware.Camera.Size optimalPrevSize = getOptimalPreviewSize(params.getSupportedPreviewSizes(), surfaceView.getWidth(), surfaceView.getHeight());
                    if (optimalPrevSize != null) {
                        params.setPreviewSize(optimalPrevSize.width, optimalPrevSize.height);
                    }
                    
                    List<String> focusModes = params.getSupportedFocusModes();
                    if (focusModes != null && focusModes.contains(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                        params.setFocusMode(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                    }
                    camera[0].setParameters(params);
                    
                    camera[0].startPreview();
                } catch (Exception e) {
                    e.printStackTrace();
                    showToast("Erro ao iniciar câmera: " + e.getMessage());
                    dialog.dismiss();
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                if (camera[0] != null) {
                    try {
                        camera[0].startPreview();
                    } catch (Exception ignored) {}
                }
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                if (camera[0] != null) {
                    camera[0].stopPreview();
                    camera[0].release();
                    camera[0] = null;
                }
            }
        });
        
        btnCapture.setOnClickListener(v -> {
            if (camera[0] != null) {
                btnCapture.setEnabled(false);
                camera[0].takePicture(null, null, (data, cam) -> {
                    java.io.File photoFile = null;
                    try {
                        // Decode raw captured bytes into a Bitmap
                        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                        
                        // Rotate bitmap physically to portrait to match display orientation
                        Matrix matrix = new Matrix();
                        matrix.postRotate(cameraRotation[0]);
                        Bitmap rotatedBitmap = Bitmap.createBitmap(
                            bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true
                        );
                        
                        // Compress rotated bitmap back to JPEG with quality 85 (perfect balance for fast upload/print)
                        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 85, bos);
                        byte[] rotatedData = bos.toByteArray();

                        photoFile = createImageFile();
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(photoFile);
                        fos.write(rotatedData);
                        fos.flush();
                        fos.close();
                        
                        if (mUploadMessage != null) {
                            mCameraPhotoUri = androidx.core.content.FileProvider.getUriForFile(MainActivity.this,
                                    getPackageName() + ".fileprovider",
                                    photoFile);
                            mUploadMessage.onReceiveValue(new Uri[]{mCameraPhotoUri});
                            mUploadMessage = null;
                        }
                        dialog.dismiss();
                    } catch (Exception e) {
                        e.printStackTrace();
                        showToast("Erro ao salvar foto: " + e.getMessage());
                        btnCapture.setEnabled(true);
                    }
                });
            }
        });
        
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        
        dialog.setOnDismissListener(d -> {
            if (camera[0] != null) {
                camera[0].stopPreview();
                camera[0].release();
                camera[0] = null;
            }
            if (mUploadMessage != null) {
                mUploadMessage.onReceiveValue(null);
                mUploadMessage = null;
            }
        });
        
        dialog.show();
    }

    private java.io.File createImageFile() throws java.io.IOException {
        String timeStamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(new java.util.Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        java.io.File storageDir = getCacheDir();
        return java.io.File.createTempFile(
            imageFileName,
            ".jpg",
            storageDir
        );
    }

    private android.hardware.Camera.Size getOptimalPictureSize(List<android.hardware.Camera.Size> sizes, int targetWidth, int targetHeight) {
        if (sizes == null) return null;
        android.hardware.Camera.Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;
        
        for (android.hardware.Camera.Size size : sizes) {
            double diff = Math.abs(size.width - targetWidth) + Math.abs(size.height - targetHeight);
            if (diff < minDiff) {
                optimalSize = size;
                minDiff = diff;
            }
        }
        return optimalSize;
    }

    private android.hardware.Camera.Size getOptimalPreviewSize(List<android.hardware.Camera.Size> sizes, int w, int h) {
        if (sizes == null) return null;
        int targetW = w > 0 ? w : 1280;
        int targetH = h > 0 ? h : 720;
        
        final double ASPECT_TOLERANCE = 0.1;
        double targetRatio = (double) Math.max(targetW, targetH) / Math.min(targetW, targetH);
        android.hardware.Camera.Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;
        int targetHeight = Math.min(targetW, targetH);
        
        for (android.hardware.Camera.Size size : sizes) {
            double ratio = (double) size.width / size.height;
            if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE) continue;
            if (Math.abs(size.height - targetHeight) < minDiff) {
                optimalSize = size;
                minDiff = Math.abs(size.height - targetHeight);
            }
        }
        
        if (optimalSize == null) {
            minDiff = Double.MAX_VALUE;
            for (android.hardware.Camera.Size size : sizes) {
                if (Math.abs(size.height - targetHeight) < minDiff) {
                    optimalSize = size;
                    minDiff = Math.abs(size.height - targetHeight);
                }
            }
        }
        return optimalSize;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == INPUT_FILE_REQUEST_CODE) {
            if (mUploadMessage == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK) {
                if (mCameraPhotoUri != null) {
                    results = new Uri[]{mCameraPhotoUri};
                }
            }
            mUploadMessage.onReceiveValue(results);
            mUploadMessage = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BT_PERMISSIONS) {
            boolean granted = true;
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }
            if (granted) {
                showToast("Permissões de Bluetooth concedidas!");
                if (this.pendingPrintBytes != null) {
                    printDirectly(this.pendingPrintBytes);
                    this.pendingPrintBytes = null;
                } else {
                    showPrinterSelectorDialog();
                }
            } else {
                showToast("Permissão de Bluetooth necessária para imprimir.");
            }
        } else if (requestCode == 1002) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openInAppCamera();
            } else {
                showToast("Permissão de câmera necessária para tirar fotos.");
                if (mUploadMessage != null) {
                    mUploadMessage.onReceiveValue(null);
                    mUploadMessage = null;
                }
            }
        }
    }

    private void showToast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        WebView webView = this.bridge.getWebView();
        if (webView != null) {
            webView.saveState(outState);
        }
        if (mCameraPhotoUri != null) {
            outState.putString("camera_photo_uri", mCameraPhotoUri.toString());
        }
    }
}
