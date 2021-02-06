package org.telegram.messenger.camera.camerax;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.drawable.BitmapDrawable;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.provider.MediaStore;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.experimental.UseExperimental;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.TorchState;
import androidx.camera.view.CameraController;
import androidx.camera.view.LifecycleCameraController;
import androidx.camera.view.PreviewView;
import androidx.camera.view.video.ExperimentalVideo;
import androidx.camera.view.video.OnVideoSavedCallback;
import androidx.camera.view.video.OutputFileOptions;
import androidx.camera.view.video.OutputFileResults;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.android.exoplayer2.util.Log;
import com.google.android.gms.vision.Frame;
import com.google.android.gms.vision.barcode.Barcode;
import com.google.android.gms.vision.barcode.BarcodeDetector;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.ChecksumException;
import com.google.zxing.FormatException;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class CameraXController implements LifecycleOwner {
    private static final ArrayList<QrCodeCallback> qrCodeAnalyzerCallbacks = new ArrayList<>();
    private static final int CORE_POOL_SIZE = 1;
    private static final int MAX_POOL_SIZE = 1;
    private static final int KEEP_ALIVE_SECONDS = 60;
    private final ArrayList<Runnable> onCamerasRequestedCallbackQueue = new ArrayList<>();
    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);
    private final Executor mainExecutor;
    private ThreadPoolExecutor threadPool;
    private LifecycleCameraController cameraController = null;
    private CameraXQrCodeScanner qrCodePlugin = null;
    private boolean receivingCamera = false;
    private boolean cameraCreated = false;
    private org.telegram.messenger.camera.CameraController.VideoTakeCallback videoCallback;

    public CameraXController(Context context) {
        mainExecutor = ContextCompat.getMainExecutor(context);
    }

    public static boolean cameraXEnabled() {
        //Log.d("CameraXController", "check: "+SharedConfig.allowCameraX);
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && SharedConfig.allowCameraX;
    }

    public static void attachQrCodeCallback(QrCodeCallback callback) {
        qrCodeAnalyzerCallbacks.add(callback);
    }

    public static void deAttachQrCodeCallback(QrCodeCallback callback) {
        qrCodeAnalyzerCallbacks.remove(callback);
    }

    public void toggleLenses() {
        cameraController.setCameraSelector(isFrontFace() ? CameraSelector.DEFAULT_BACK_CAMERA : CameraSelector.DEFAULT_FRONT_CAMERA);
    }

    public void toggleLenses(boolean frontFace) {
        cameraController.setCameraSelector(frontFace ? CameraSelector.DEFAULT_BACK_CAMERA : CameraSelector.DEFAULT_FRONT_CAMERA);
    }

    public void initCameraEngine(Activity activity, PreviewView previewer, boolean frontFace, Runnable onCamerasRequested) {
        if (receivingCamera) {
            onCamerasRequestedCallbackQueue.add(onCamerasRequested);
            return;
        } else {
            if (cameraCreated) {
                onCamerasRequested.run();
                return;
            }
        }

        receivingCamera = true;

        if (threadPool == null) threadPool = new ThreadPoolExecutor(CORE_POOL_SIZE, MAX_POOL_SIZE, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        if (qrCodePlugin == null) qrCodePlugin = new CameraXQrCodeScanner();

        onCamerasRequestedCallbackQueue.add(onCamerasRequested);

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START);

        cameraController = new LifecycleCameraController(activity);
        cameraController.bindToLifecycle(this);
        previewer.setController(cameraController);
        cameraController.setImageAnalysisAnalyzer(threadPool, qrCodePlugin);

        cameraController.getInitializationFuture().addListener(() -> {
            receivingCamera = false;
            cameraCreated = true;

            if (frontFace) cameraController.setCameraSelector(CameraSelector.DEFAULT_FRONT_CAMERA);

            for (Runnable callback : onCamerasRequestedCallbackQueue) {
                callback.run();
            }

            onCamerasRequestedCallbackQueue.clear();
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.cameraInitied);
        }, mainExecutor);
    }

    public int getCurrentFlashMode() {
        if (cameraController == null) return TorchState.OFF;
        return cameraController.getTorchState().getValue();
    }

    public boolean hasFrontFaceCamera() {
        if (cameraController == null) return false;
        return cameraController.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA);
    }

    public int getNextFlashMode() {
        return getCurrentFlashMode() == TorchState.ON ? TorchState.OFF : TorchState.ON;
    }

    public void setFlashMode(int mode) {
        cameraController.enableTorch(mode == TorchState.ON);
    }

    public void setFlashMode(boolean enable) {
        cameraController.enableTorch(enable);
    }

    public boolean isFrontFace() {
        return cameraController.getCameraSelector() == CameraSelector.DEFAULT_FRONT_CAMERA;
    }

    public boolean isCameraInitialized() {
        return cameraController != null && cameraCreated && !receivingCamera;
    }

    public void onPause() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE);
    }

    public void onResume() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME);
    }

    public void onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        receivingCamera = false;
        cameraCreated = false;
        if (cameraController != null) cameraController.unbind();
        cameraController = null;
        if (threadPool != null) threadPool.shutdown();
        threadPool = null;
        if (qrCodePlugin != null) qrCodePlugin.release();
        qrCodePlugin = null;
    }

    @UseExperimental(markerClass = ExperimentalVideo.class)
    public void startRecordingVideo(@Nullable File output, boolean mirror, final org.telegram.messenger.camera.CameraController.VideoTakeCallback callback, final Runnable onVideoStartRecord) {
        if (output == null) return;
        FileLog.d("[CameraXController] startRecordingVideo (to = " + output.getAbsolutePath() + "");

        videoCallback = callback;

        cameraController.setEnabledUseCases(CameraController.VIDEO_CAPTURE);
        cameraController.startRecording(OutputFileOptions.builder(output).build(), threadPool, new OnVideoSavedCallback() {
            @Override
            public void onVideoSaved(@NonNull OutputFileResults outputFileResults) {
                FileLog.d("[CameraXController] onVideoSaved: " + outputFileResults.toString());
                MediaMetadataRetriever mediaMetadataRetriever = null;
                long duration = 0;
                try {
                    mediaMetadataRetriever = new MediaMetadataRetriever();
                    mediaMetadataRetriever.setDataSource(output.getAbsolutePath());
                    String d = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (d != null) {
                        duration = (int) Math.ceil(Long.parseLong(d) / 1000.0f);
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                } finally {
                    try {
                        if (mediaMetadataRetriever != null) {
                            mediaMetadataRetriever.release();
                        }
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
                Bitmap bitmap = SendMessagesHelper.createVideoThumbnail(output.getAbsolutePath(), MediaStore.Video.Thumbnails.MINI_KIND);
                if (mirror) {
                    Bitmap b = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(b);
                    canvas.scale(-1, 1, b.getWidth() / 2, b.getHeight() / 2);
                    canvas.drawBitmap(bitmap, 0, 0, null);
                    bitmap.recycle();
                    bitmap = b;
                }
                String fileName = Integer.MIN_VALUE + "_" + SharedConfig.getLastLocalId() + ".jpg";
                final File cacheFile = new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), fileName);
                try {
                    FileOutputStream stream = new FileOutputStream(cacheFile);
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 87, stream);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                SharedConfig.saveConfig();
                final long durationFinal = duration;
                final Bitmap bitmapFinal = bitmap;

                AndroidUtilities.runOnUIThread(() -> {
                    if (videoCallback != null) {
                        String path = cacheFile.getAbsolutePath();
                        if (bitmapFinal != null) {
                            ImageLoader.getInstance().putImageToCache(new BitmapDrawable(bitmapFinal), Utilities.MD5(path));
                        }
                        videoCallback.onFinishVideoRecording(path, durationFinal);
                        videoCallback = null;
                    }
                });
            }

            @Override
            public void onError(int videoCaptureError, @NonNull String message, @Nullable Throwable cause) {
                Log.d("CameraXController", "Error: " + message + " (err = " + videoCaptureError + ")");
                cause.printStackTrace();
                videoCallback = null;
            }
        });

        if (onVideoStartRecord != null) AndroidUtilities.runOnUIThread(onVideoStartRecord);
    }

    @UseExperimental(markerClass = ExperimentalVideo.class)
    public void stopVideoRecording() {
        FileLog.d("[CameraXController] stopVideoRecording");
        cameraController.stopRecording();
        cameraController.setEnabledUseCases(CameraController.IMAGE_CAPTURE | CameraController.IMAGE_ANALYSIS);
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    public void takePicture(File cameraFile, Runnable onPictureTaken) {
        FileLog.d("[CameraXController] takePicture = " + cameraFile.toString());
        cameraController.setEnabledUseCases(CameraController.IMAGE_CAPTURE | CameraController.IMAGE_ANALYSIS);
        cameraController.takePicture(new ImageCapture.OutputFileOptions.Builder(cameraFile).build(), mainExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                onPictureTaken.run();
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                exception.printStackTrace();
            }
        });
    }

    public void detachPreview(PreviewView previewView) {
        previewView.setController(null);
    }

    public void attachPreview(PreviewView previewView) {
        previewView.setController(cameraController);
    }

    public void setZoom(float zoom) {
        cameraController.setZoomRatio(zoom);
    }

    public interface QrCodeCallback {
        void onQrScanned(String code);
    }

    private static class CameraXQrCodeScanner implements ImageAnalysis.Analyzer {
        private final QRCodeReader zxingReader = new QRCodeReader();
        private final BarcodeDetector visionQrReader = new BarcodeDetector.Builder(ApplicationLoader.applicationContext).setBarcodeFormats(Barcode.QR_CODE).build();

        @Override
        public void analyze(@NonNull ImageProxy image) {
            if (
                    (image.getFormat() != ImageFormat.YUV_422_888 && image.getFormat() != ImageFormat.YUV_444_888 && image.getFormat() != ImageFormat.YUV_420_888)
                            || qrCodeAnalyzerCallbacks.size() == 0
            ) {
                image.close();
                return;
            }

            ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            int width = image.getWidth();
            int height = image.getHeight();

            try {
                String code;

                if (visionQrReader.isOperational()) {
                    code = visionImpl(plane, width, height);
                } else {
                    code = zxingImpl(plane, width, height);
                }

                if (code != null) notifyCallbacks(code);
            } catch (Exception ignored) {
                ignored.printStackTrace();
            }

            image.close();
        }

        public void release() {
            visionQrReader.release();
        }

        private String visionImpl(ImageProxy.PlaneProxy plane, int width, int height) {
            Frame frame = new Frame.Builder().setImageData(plane.getBuffer(), width, height, ImageFormat.NV21).build();
            SparseArray<Barcode> codes = visionQrReader.detect(frame);

            String value;
            if (codes.size() > 0) {
                value = codes.valueAt(0).rawValue;
            } else {
                value = null;
            }
            return value;
        }

        private String zxingImpl(ImageProxy.PlaneProxy plane, int width, int height) throws FormatException, ChecksumException, NotFoundException {
            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                    bufferAsBytes(plane.getBuffer()),
                    width,
                    height,
                    0,
                    0,
                    width,
                    height,
                    false
            );

            BinaryBitmap bb = new BinaryBitmap(new HybridBinarizer(source));
            Result result = zxingReader.decode(bb);
            if (result == null) return null;
            return result.getText();
        }

        private byte[] bufferAsBytes(ByteBuffer buffer) {
            buffer.rewind();

            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        }

        private void notifyCallbacks(String code) {
            for (QrCodeCallback c : qrCodeAnalyzerCallbacks) {
                c.onQrScanned(code);
            }

            //qrCodeAnalyzerCallbacks.clear();
        }
    }
}
