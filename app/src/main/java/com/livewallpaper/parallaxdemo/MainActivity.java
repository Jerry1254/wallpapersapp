package com.livewallpaper.parallaxdemo;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private ParallaxPreviewView preview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(11, 9, 18));
        getWindow().setNavigationBarColor(Color.rgb(11, 9, 18));

        FrameLayout root = new FrameLayout(this);
        preview = new ParallaxPreviewView(this);
        root.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setGravity(Gravity.CENTER_HORIZONTAL);
        controls.setPadding(dp(24), dp(18), dp(24), dp(28));

        GradientDrawable panel = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00110D1C, 0xE6110D1C}
        );
        controls.setBackground(panel);

        TextView title = new TextView(this);
        title.setText("4D 分层壁纸验证");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        controls.addView(title);

        TextView tip = new TextView(this);
        tip.setText(R.string.preview_tip);
        tip.setTextColor(0xFFD8D3E4);
        tip.setTextSize(14);
        tip.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tipParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        tipParams.topMargin = dp(6);
        tipParams.bottomMargin = dp(18);
        controls.addView(tip, tipParams);

        Button setButton = new Button(this);
        setButton.setText(R.string.set_wallpaper);
        setButton.setTextColor(Color.WHITE);
        setButton.setTextSize(16);
        setButton.setAllCaps(false);
        GradientDrawable buttonBackground = new GradientDrawable();
        buttonBackground.setColor(0xFF7C3AED);
        buttonBackground.setCornerRadius(dp(28));
        setButton.setBackground(buttonBackground);
        setButton.setOnClickListener(view -> openWallpaperPicker());
        controls.addView(setButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56)
        ));

        FrameLayout.LayoutParams controlsParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        );
        root.addView(controls, controlsParams);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        preview.start();
    }

    @Override
    protected void onPause() {
        preview.stop();
        super.onPause();
    }

    private void openWallpaperPicker() {
        ComponentName component = new ComponentName(this, ParallaxWallpaperService.class);
        Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component);
        try {
            startActivity(intent);
        } catch (RuntimeException unavailable) {
            startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
