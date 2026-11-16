package com.tomato.tApp;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.ExifInterface;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

final class ImageUtil {

    static final int MODE_ORIGINAL = 0;
    static final int MODE_GRAY = 1;
    static final int MODE_BW = 2;
    static final int MODE_ENHANCE = 3;

    private ImageUtil() {
    }

    /** Decodes a JPEG, keeps at most ~maxDim pixels on the long side, and applies EXIF rotation. */
    static Bitmap decodeFile(File file, int maxDim) {
        try {
            String path = file.getAbsolutePath();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int longSide = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (longSide / (sample * 2) >= maxDim) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeFile(path, opts);
            if (bmp == null) return null;

            Matrix m = exifMatrix(path);
            if (m == null) return bmp;
            Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (rotated != bmp) bmp.recycle();
            return rotated;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Matrix exifMatrix(String path) {
        int o;
        try {
            o = new ExifInterface(path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException e) {
            return null;
        }
        Matrix m = new Matrix();
        switch (o) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                m.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                m.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                m.postRotate(270);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                m.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                m.postScale(1, -1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                m.postRotate(90);
                m.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                m.postRotate(270);
                m.postScale(-1, 1);
                break;
            default:
                return null;
        }
        return m;
    }

    static void saveJpeg(Bitmap bmp, File file, int quality) throws IOException {
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(file), 64 * 1024)) {
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, quality, os)) {
                throw new IOException("Could not write image");
            }
        }
    }

    /** Colour matrix for a filter mode plus brightness (-100..100) and contrast (0..2). */
    static ColorMatrix matrix(int mode, float brightness, float contrast) {
        ColorMatrix cm = new ColorMatrix();
        switch (mode) {
            case MODE_GRAY:
                cm.setSaturation(0f);
                break;
            case MODE_BW: {
                float k = 3f;
                float off = 128f - 140f * k;
                cm.set(new float[]{
                        k * .299f, k * .587f, k * .114f, 0, off,
                        k * .299f, k * .587f, k * .114f, 0, off,
                        k * .299f, k * .587f, k * .114f, 0, off,
                        0, 0, 0, 1, 0});
                break;
            }
            case MODE_ENHANCE: {
                float s = 1.25f;
                float t = (1f - s) * 128f + 8f;
                cm.set(new float[]{
                        s, 0, 0, 0, t,
                        0, s, 0, 0, t,
                        0, 0, s, 0, t,
                        0, 0, 0, 1, 0});
                break;
            }
            default:
                break;
        }
        float t = (1f - contrast) * 128f + brightness;
        cm.postConcat(new ColorMatrix(new float[]{
                contrast, 0, 0, 0, t,
                0, contrast, 0, 0, t,
                0, 0, contrast, 0, t,
                0, 0, 0, 1, 0}));
        return cm;
    }

    static Bitmap applyAdjust(Bitmap src, int mode, float brightness, float contrast, int rotation) {
        Bitmap rotated = src;
        if (rotation % 360 != 0) {
            Matrix m = new Matrix();
            m.postRotate(rotation);
            rotated = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        }
        Bitmap out = Bitmap.createBitmap(rotated.getWidth(), rotated.getHeight(), Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setColorFilter(new ColorMatrixColorFilter(matrix(mode, brightness, contrast)));
        new Canvas(out).drawBitmap(rotated, 0, 0, paint);
        if (rotated != src) rotated.recycle();
        return out;
    }
}
