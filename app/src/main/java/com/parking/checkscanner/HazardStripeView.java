package com.parking.checkscanner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import androidx.core.content.ContextCompat;

/**
 * 黄黑 45° 斜纹警示条，用于标题栏下沿与底部弹层顶部
 */
public class HazardStripeView extends View {

    // 单条斜纹宽度 (dp)
    private static final float STRIPE_WIDTH_DP = 8f;

    private final Paint stripePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path stripePath = new Path();
    private final int backgroundColor;
    private final float stripeWidth;

    public HazardStripeView(Context context) {
        this(context, null);
    }

    public HazardStripeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        stripePaint.setStyle(Paint.Style.FILL);
        stripePaint.setColor(ContextCompat.getColor(context, R.color.hazard));
        backgroundColor = ContextCompat.getColor(context, R.color.ink);
        stripeWidth = STRIPE_WIDTH_DP * getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        stripePath.reset();
        // 平行四边形依次铺满，左右各多铺一个高度保证两端不留空
        for (float x = -h; x < w + h; x += stripeWidth * 2) {
            stripePath.moveTo(x, h);
            stripePath.lineTo(x + h, 0);
            stripePath.lineTo(x + h + stripeWidth, 0);
            stripePath.lineTo(x + stripeWidth, h);
            stripePath.close();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(backgroundColor);
        canvas.drawPath(stripePath, stripePaint);
    }
}
