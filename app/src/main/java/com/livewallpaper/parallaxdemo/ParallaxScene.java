package com.livewallpaper.parallaxdemo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;

import java.io.IOException;
import java.io.InputStream;

final class ParallaxScene {
    private static final String[] FILES = {
            "wallpapers/demo/background.jpg",
            "wallpapers/demo/buildings.png",
            "wallpapers/demo/light.png",
            "wallpapers/demo/character.png",
            "wallpapers/demo/debris.png"
    };

    private static final float[] DEPTHS = {0.06f, 0.28f, 0.50f, 0.80f, 1.0f};
    private static final float OVERSCAN = 1.18f;

    private final Bitmap[] layers = new Bitmap[FILES.length];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();

    ParallaxScene(Context context) {
        for (int i = 0; i < FILES.length; i++) {
            layers[i] = loadBitmap(context, FILES[i], i == 0);
        }
    }

    void draw(Canvas canvas, float tiltX, float tiltY) {
        canvas.drawColor(0xFF000000);
        int width = canvas.getWidth();
        int height = canvas.getHeight();

        for (int i = 0; i < layers.length; i++) {
            Bitmap bitmap = layers[i];
            if (bitmap == null || bitmap.isRecycled()) continue;

            float coverScale = Math.max(
                    width / (float) bitmap.getWidth(),
                    height / (float) bitmap.getHeight()
            );
            float scale = coverScale * OVERSCAN;
            float scaledWidth = bitmap.getWidth() * scale;
            float scaledHeight = bitmap.getHeight() * scale;
            float centeredX = (width - scaledWidth) * 0.5f;
            float centeredY = (height - scaledHeight) * 0.5f;

            float motionX = -tiltX * width * 0.09f * DEPTHS[i];
            float motionY = tiltY * height * 0.05f * DEPTHS[i];

            matrix.reset();
            matrix.postScale(scale, scale);
            matrix.postTranslate(centeredX + motionX, centeredY + motionY);
            canvas.drawBitmap(bitmap, matrix, paint);
        }
    }

    void release() {
        for (Bitmap layer : layers) {
            if (layer != null && !layer.isRecycled()) layer.recycle();
        }
    }

    private static Bitmap loadBitmap(Context context, String file, boolean background) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = background
                ? Bitmap.Config.RGB_565
                : Bitmap.Config.ARGB_8888;
        try (InputStream input = context.getAssets().open(file)) {
            return BitmapFactory.decodeStream(input, null, options);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot load wallpaper layer: " + file, error);
        }
    }
}
