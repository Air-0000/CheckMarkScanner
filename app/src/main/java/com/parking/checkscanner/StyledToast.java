package com.parking.checkscanner;

import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.lifecycle.Lifecycle;

/**
 * 黑底黄框样式的 Toast，替代系统默认提示
 */
public final class StyledToast {

    // 距屏幕底部的偏移 (dp)，避开底部操作栏
    private static final int BOTTOM_OFFSET_DP = 96;

    private StyledToast() {}

    @SuppressWarnings("deprecation")
    public static void show(Context context, CharSequence text, int duration) {
        // Android 11+ 只在前台显示自定义视图 Toast，应用在后台时退回系统样式，保证提示不丢
        if (!(context instanceof ComponentActivity)
                || !((ComponentActivity) context).getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
            Toast.makeText(context, text, duration).show();
            return;
        }

        View view = LayoutInflater.from(context).inflate(R.layout.toast_styled, null);
        ((TextView) view.findViewById(R.id.tv_toast_text)).setText(text);

        Toast toast = new Toast(context);
        toast.setView(view);
        toast.setDuration(duration);
        int offset = Math.round(BOTTOM_OFFSET_DP * context.getResources().getDisplayMetrics().density);
        toast.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, offset);
        toast.show();
    }
}
