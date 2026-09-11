package com.livewallpaper.parallaxdemo;

import android.graphics.Canvas;
import android.os.Handler;
import android.os.HandlerThread;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

public final class ParallaxWallpaperService extends WallpaperService {
    @Override
    public Engine onCreateEngine() {
        return new ParallaxEngine();
    }

    private final class ParallaxEngine extends Engine implements TiltSensor.Listener {
        private final HandlerThread renderThread = new HandlerThread("parallax-wallpaper-render");
        private Handler renderHandler;
        private ParallaxScene scene;
        private TiltSensor tiltSensor;
        private volatile float targetX;
        private volatile float targetY;
        private float currentX;
        private float currentY;
        private boolean visible;
        private boolean surfaceReady;

        private final Runnable drawFrame = new Runnable() {
            @Override
            public void run() {
                draw();
                if (visible && surfaceReady) {
                    renderHandler.postDelayed(this, 20L);
                }
            }
        };

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            setOffsetNotificationsEnabled(false);
            renderThread.start();
            renderHandler = new Handler(renderThread.getLooper());
            renderHandler.post(() -> scene = new ParallaxScene(ParallaxWallpaperService.this));
            tiltSensor = new TiltSensor(ParallaxWallpaperService.this, this);
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            surfaceReady = true;
            scheduleDraw();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            scheduleDraw();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            surfaceReady = false;
            if (renderHandler != null) renderHandler.removeCallbacks(drawFrame);
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onVisibilityChanged(boolean isVisible) {
            visible = isVisible;
            if (isVisible) {
                tiltSensor.start(renderHandler);
                scheduleDraw();
            } else {
                tiltSensor.stop();
                if (renderHandler != null) renderHandler.removeCallbacks(drawFrame);
            }
        }

        @Override
        public void onTilt(float x, float y) {
            targetX = x;
            targetY = y;
        }

        @Override
        public void onDestroy() {
            visible = false;
            surfaceReady = false;
            tiltSensor.stop();
            if (renderHandler != null) {
                renderHandler.removeCallbacksAndMessages(null);
                renderHandler.post(() -> {
                    if (scene != null) scene.release();
                });
            }
            renderThread.quitSafely();
            super.onDestroy();
        }

        private void scheduleDraw() {
            if (renderHandler == null || !visible || !surfaceReady) return;
            renderHandler.removeCallbacks(drawFrame);
            renderHandler.post(drawFrame);
        }

        private void draw() {
            if (scene == null || !surfaceReady) return;
            currentX += (targetX - currentX) * 0.24f;
            currentY += (targetY - currentY) * 0.24f;

            SurfaceHolder holder = getSurfaceHolder();
            Canvas canvas = null;
            try {
                canvas = holder.lockHardwareCanvas();
                if (canvas != null) scene.draw(canvas, currentX, currentY);
            } catch (RuntimeException ignored) {
                // Surface can disappear between visibility and draw callbacks.
            } finally {
                if (canvas != null) holder.unlockCanvasAndPost(canvas);
            }
        }
    }
}
