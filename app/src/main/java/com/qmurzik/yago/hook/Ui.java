package com.qmurzik.yago.hook;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

/**
 * Оформление элементов модуля внутри чужого приложения.
 * Ресурсы модуля там недоступны, поэтому всё рисуется кодом.
 */
final class Ui {
    static final int ACCENT = 0xFFFCE000;      // жёлтый Яндекс Go
    static final int ON_ACCENT = 0xFF21201F;
    static final int HIGHLIGHT = 0xFF2E7DFF;

    final Context ctx;
    final boolean dark;
    final int surface, onSurface, onSurfaceVariant, outline, tonal;

    Ui(Context ctx) {
        this.ctx = ctx;
        dark = (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        surface = dark ? 0xFF242426 : 0xFFFFFFFF;
        onSurface = dark ? 0xFFF2F2F2 : 0xFF21201F;
        onSurfaceVariant = dark ? 0xFFA9A9AD : 0xFF75757A;
        outline = dark ? 0x33FFFFFF : 0x1F000000;
        tonal = dark ? 0xFF35353A : 0xFFF1F0ED;
    }

    int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()));
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    TextView text(CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    /** Кнопка-«пилюля»: primary — жёлтая, иначе — тональная. */
    TextView button(CharSequence s, boolean primary) {
        TextView b = text(s, 14, primary ? ON_ACCENT : onSurface, true);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(44));
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setSingleLine(true);
        int bg = primary ? ACCENT : tonal;
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x33FFFFFF : 0x22000000),
                round(bg, dp(22)), null));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(ctx, dark
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
    }

    static int alpha(int color, int a) {
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }
}
