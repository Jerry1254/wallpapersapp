package com.livewallpaper.parallaxdemo;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

final class ParallaxPreviewView extends View implements TiltSensor.Listener {
    private final ParallaxScene scene;
    private final TiltSensor tiltSensor;
    private float targetX;
    private float targetY;
    private float currentX;
    private float currentY;
    private boolean running;

    ParallaxPreviewView(Context context) {
        super(context);
        scene = new ParallaxScene(context);
        tiltSensor = new TiltSensor(context, this);
    }

    void start() {
        if (running) return;
        running = true;
        tiltSensor.start();
        postInvalidateOnAnimation();
    }

    void stop() {
        running = false;
        tiltSensor.stop();
    }

    @Override
    public void onTilt(float x, float y) {
        targetX = x;
        targetY = y;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        currentX += (targetX - currentX) * 0.22f;
        currentY += (targetY - currentY) * 0.22f;
        scene.draw(canvas, currentX, currentY);
        if (running) postInvalidateOnAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        scene.release();
        super.onDetachedFromWindow();
    }
}
