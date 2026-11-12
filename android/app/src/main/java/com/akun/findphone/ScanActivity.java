package com.akun.findphone;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * QRコードを読み取る画面。読み取った文字列を EXTRA_TEXT に入れて返す。
 * 構成を小さく保つため、扱いの簡単な旧カメラAPI(android.hardware.Camera)を使う。
 */
@SuppressWarnings("deprecation")
public class ScanActivity extends LocalizedActivity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    static final String EXTRA_TEXT = "text";
    private static final int REQ_CAMERA = 1;

    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final QRCodeReader reader = new QRCodeReader();
    private SurfaceView preview;
    private Camera camera;
    private int frameWidth;
    private int frameHeight;
    private boolean surfaceReady;
    private volatile boolean decoding;
    private volatile boolean finished;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.setFitsSystemWindows(true);

        preview = new SurfaceView(this);
        preview.getHolder().addCallback(this);
        root.addView(preview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        TextView hint = Ui.text(this, getString(R.string.scan_hint), 15, 0xFFFFFFFF, false);
        hint.setGravity(Gravity.CENTER);
        hint.setBackgroundColor(0xAA000000);
        int p = Ui.dp(this, 16);
        hint.setPadding(p, p, p, p);
        root.addView(hint, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);

        if (!hasCameraPermission()) requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        if (request != REQ_CAMERA) return;
        if (hasCameraPermission()) {
            startCamera();
        } else {
            Toast.makeText(this, R.string.camera_needed, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startCamera();
    }

    @Override
    protected void onPause() {
        stopCamera();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        decoder.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        startCamera();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // 何もしない(プレビューは surfaceCreated で開始済み)
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        stopCamera();
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void startCamera() {
        if (camera != null || !surfaceReady || !hasCameraPermission()) return;
        try {
            camera = Camera.open(); // 背面カメラ
            if (camera == null) throw new IllegalStateException("no back camera");
            Camera.Parameters params = camera.getParameters();
            List<String> focusModes = params.getSupportedFocusModes();
            if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                camera.setParameters(params);
            }
            Camera.Size size = params.getPreviewSize();
            frameWidth = size.width;
            frameHeight = size.height;
            fitPreview();
            camera.setDisplayOrientation(90); // 縦持ち用に回転
            camera.setPreviewDisplay(preview.getHolder());
            camera.setPreviewCallback(this);
            camera.startPreview();
        } catch (Exception e) {
            AppLog.add(getString(R.string.log_error, e.toString()));
            stopCamera();
            Toast.makeText(this, R.string.camera_needed, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    /** 映像が縦横に引き伸ばされないよう、プレビューの高さをカメラ映像の比率に合わせる。 */
    private void fitPreview() {
        int width = preview.getWidth();
        if (width == 0 || frameHeight == 0) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) preview.getLayoutParams();
        lp.height = width * frameWidth / frameHeight; // 映像は90度回転して表示される
        preview.setLayoutParams(lp);
    }

    private void stopCamera() {
        if (camera == null) return;
        try {
            camera.setPreviewCallback(null);
            camera.stopPreview();
        } catch (Exception ignored) {
            // 既に停止済み
        }
        camera.release();
        camera = null;
    }

    /** カメラの1コマごとに呼ばれる。解析は別スレッドで行い、解析中のコマは捨てる。 */
    @Override
    public void onPreviewFrame(byte[] frame, Camera c) {
        if (finished || decoding || decoder.isShutdown()) return;
        decoding = true;
        final int w = frameWidth;
        final int h = frameHeight;
        decoder.execute(() -> {
            try {
                String text = reader.decode(new BinaryBitmap(new HybridBinarizer(
                        new PlanarYUVLuminanceSource(frame, w, h, 0, 0, w, h, false)))).getText();
                finished = true;
                runOnUiThread(() -> {
                    setResult(RESULT_OK, new Intent().putExtra(EXTRA_TEXT, text));
                    finish();
                });
            } catch (ReaderException e) {
                // このコマにQRコードは無い
            } catch (Exception e) {
                // 壊れたコマは無視
            } finally {
                reader.reset();
                decoding = false;
            }
        });
    }
}
