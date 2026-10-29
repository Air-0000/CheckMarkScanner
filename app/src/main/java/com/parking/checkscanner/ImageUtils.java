package com.parking.checkscanner;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import java.io.InputStream;

public class ImageUtils {

    // 最佳标准化处理分辨率（兼顾高精度与极速计算，防止大图 OOM 与 32位整数溢出）
    public static final int MAX_TARGET_DIMENSION = 1440;

    /**
     * 安全读取图片：自动读取 EXIF 旋转角、等比降采样防止内存溢出、自动校正至竖向考勤表朝向
     */
    public static Bitmap loadAndNormalizeBitmap(Context context, Uri uri) {
        return loadBitmap(context, uri, MAX_TARGET_DIMENSION, true);
    }

    /**
     * 读取图片但不缩到 576 x 1024 (手写废料表需要保留笔画细节)：长边降采样到不超过 maxDim 的量级，
     * 同样处理 EXIF 旋转与横图扶正
     */
    public static Bitmap loadHighResBitmap(Context context, Uri uri, int maxDim) {
        Bitmap b = loadBitmap(context, uri, maxDim, false);
        if (b == null || Math.max(b.getWidth(), b.getHeight()) <= maxDim) return b;
        float s = (float) maxDim / Math.max(b.getWidth(), b.getHeight());
        Bitmap scaled = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * s), Math.round(b.getHeight() * s), true);
        if (scaled != b) b.recycle();
        return scaled;
    }

    private static Bitmap loadBitmap(Context context, Uri uri, int maxTargetDimension, boolean normalize) {
        if (context == null || uri == null) return null;
        try {
            // 1. 获取原图物理尺寸
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                if (is == null) return null;
                BitmapFactory.decodeStream(is, null, options);
            }

            int srcW = options.outWidth;
            int srcH = options.outHeight;
            if (srcW <= 0 || srcH <= 0) return null;

            // 2. 计算合理采样率 inSampleSize
            int inSampleSize = 1;
            int maxDim = Math.max(srcW, srcH);
            while (maxDim / (inSampleSize * 2) >= maxTargetDimension) {
                inSampleSize *= 2;
            }

            // 3. 解码 Bitmap
            options.inJustDecodeBounds = false;
            options.inSampleSize = inSampleSize;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap decoded;
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                if (is == null) return null;
                decoded = BitmapFactory.decodeStream(is, null, options);
            }
            if (decoded == null) return null;

            // 4. 解析 EXIF 方向标签（解决手机拍照90度/270度横向存储问题）
            int rotationAngle = 0;
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                if (is != null) {
                    ExifInterface exif = new ExifInterface(is);
                    int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                    if (orientation == ExifInterface.ORIENTATION_ROTATE_90) {
                        rotationAngle = 90;
                    } else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) {
                        rotationAngle = 180;
                    } else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) {
                        rotationAngle = 270;
                    }
                }
            } catch (Exception ignored) {}

            Bitmap oriented = decoded;
            if (rotationAngle != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotationAngle);
                oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
                if (oriented != decoded) {
                    decoded.recycle();
                }
            }

            // 5. 确保考勤表为竖直方向（表格高大于宽）
            // 如果用户拍照或相册选图为横向，自动顺时针旋转90度扶正
            if (oriented.getWidth() > oriented.getHeight()) {
                Matrix matrix = new Matrix();
                matrix.postRotate(90);
                Bitmap portrait = Bitmap.createBitmap(oriented, 0, 0, oriented.getWidth(), oriented.getHeight(), matrix, true);
                if (portrait != oriented) {
                    oriented.recycle();
                }
                oriented = portrait;
            }

            // 6. 统一标准化定标：将输入图像统一规范为 576 x 1024 标准基准尺寸
            // 彻底解决高分手机相机照片导致笔画像素暴涨、内存溢出与识别严重错位问题
            if (normalize && (oriented.getWidth() != 576 || oriented.getHeight() != 1024)) {
                Bitmap normalized = Bitmap.createScaledBitmap(oriented, 576, 1024, true);
                if (normalized != oriented) {
                    oriented.recycle();
                }
                oriented = normalized;
            }

            return oriented;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
