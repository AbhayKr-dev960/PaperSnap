package com.tomato.tApp;

import android.graphics.Bitmap;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the sheet of paper in a photo and flattens it (perspective correction).
 * Corners are always passed around as fractions of the image (0..1), in the order
 * top-left, top-right, bottom-right, bottom-left: x0,y0,x1,y1,x2,y2,x3,y3.
 */
final class Scanner {

    private static boolean ready;

    private Scanner() {
    }

    private static synchronized boolean init() {
        if (!ready) ready = OpenCVLoader.initLocal();
        return ready;
    }

    /** Returns the page corners, or null if no clear page was found. */
    static float[] detect(Bitmap bmp) {
        if (!init()) return null;
        Mat rgba = new Mat();
        Mat small = new Mat();
        Mat gray = new Mat();
        try {
            Utils.bitmapToMat(bmp, rgba);
            double scale = Math.min(1.0, 800.0 / Math.max(rgba.cols(), rgba.rows()));
            Imgproc.resize(rgba, small, new Size(), scale, scale, Imgproc.INTER_AREA);
            Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY);
            Imgproc.GaussianBlur(gray, gray, new Size(5, 5), 0);

            double area = (double) small.cols() * small.rows();
            Point[] p = fromEdges(gray, area);
            if (p == null) p = fromThreshold(gray, area);
            if (p == null) return null;

            p = order(p);
            float[] q = new float[8];
            for (int i = 0; i < 4; i++) {
                q[2 * i] = (float) (p[i].x / small.cols());
                q[2 * i + 1] = (float) (p[i].y / small.rows());
            }
            return q;
        } catch (Throwable t) {
            return null;
        } finally {
            rgba.release();
            small.release();
            gray.release();
        }
    }

    private static Point[] fromEdges(Mat gray, double imgArea) {
        Mat edges = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
        try {
            Imgproc.Canny(gray, edges, 50, 150);
            Imgproc.dilate(edges, edges, kernel, new Point(-1, -1), 2);
            return biggestQuad(edges, imgArea);
        } finally {
            edges.release();
            kernel.release();
        }
    }

    private static Point[] fromThreshold(Mat gray, double imgArea) {
        Mat bw = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(7, 7));
        try {
            Imgproc.threshold(gray, bw, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
            Imgproc.morphologyEx(bw, bw, Imgproc.MORPH_CLOSE, kernel);
            Imgproc.morphologyEx(bw, bw, Imgproc.MORPH_OPEN, kernel);
            return biggestQuad(bw, imgArea);
        } finally {
            bw.release();
            kernel.release();
        }
    }

    private static Point[] biggestQuad(Mat binary, double imgArea) {
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Mat work = binary.clone();
        Point[] best = null;
        double bestArea = 0;
        try {
            Imgproc.findContours(work, contours, hierarchy,
                    Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
            for (MatOfPoint c : contours) {
                double area = Imgproc.contourArea(c);
                // too small = noise, nearly the whole frame = the photo border, not a page
                if (area < imgArea * 0.15 || area > imgArea * 0.97 || area <= bestArea) continue;

                MatOfPoint2f c2 = new MatOfPoint2f(c.toArray());
                MatOfPoint2f approx = new MatOfPoint2f();
                double peri = Imgproc.arcLength(c2, true);
                Imgproc.approxPolyDP(c2, approx, 0.02 * peri, true);
                MatOfPoint approxInt = new MatOfPoint(approx.toArray());
                if (approx.total() == 4 && Imgproc.isContourConvex(approxInt)) {
                    best = approx.toArray();
                    bestArea = area;
                }
                c2.release();
                approx.release();
                approxInt.release();
            }
        } finally {
            for (MatOfPoint c : contours) c.release();
            hierarchy.release();
            work.release();
        }
        return best;
    }

    private static Point[] order(Point[] pts) {
        Point tl = pts[0], tr = pts[0], br = pts[0], bl = pts[0];
        double minS = Double.MAX_VALUE, maxS = -Double.MAX_VALUE;
        double minD = Double.MAX_VALUE, maxD = -Double.MAX_VALUE;
        for (Point p : pts) {
            double s = p.x + p.y;
            double d = p.y - p.x;
            if (s < minS) { minS = s; tl = p; }
            if (s > maxS) { maxS = s; br = p; }
            if (d < minD) { minD = d; tr = p; }
            if (d > maxD) { maxD = d; bl = p; }
        }
        return new Point[]{tl, tr, br, bl};
    }

    /** Cuts the quad out of the bitmap and flattens it into a rectangle. */
    static Bitmap warp(Bitmap bmp, float[] q) {
        if (!init()) return bmp;
        int W = bmp.getWidth();
        int H = bmp.getHeight();
        Point tl = new Point(q[0] * W, q[1] * H);
        Point tr = new Point(q[2] * W, q[3] * H);
        Point br = new Point(q[4] * W, q[5] * H);
        Point bl = new Point(q[6] * W, q[7] * H);

        double w = Math.max(dist(tl, tr), dist(bl, br));
        double h = Math.max(dist(tl, bl), dist(tr, br));
        w = Math.max(16, Math.min(w, 6000));
        h = Math.max(16, Math.min(h, 6000));

        Mat src = new Mat();
        Mat out = new Mat();
        MatOfPoint2f from = new MatOfPoint2f(tl, tr, br, bl);
        MatOfPoint2f to = new MatOfPoint2f(
                new Point(0, 0), new Point(w - 1, 0),
                new Point(w - 1, h - 1), new Point(0, h - 1));
        Mat transform = null;
        try {
            Utils.bitmapToMat(bmp, src);
            transform = Imgproc.getPerspectiveTransform(from, to);
            Imgproc.warpPerspective(src, out, transform, new Size(w, h), Imgproc.INTER_CUBIC);
            Bitmap result = Bitmap.createBitmap((int) w, (int) h, Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(out, result);
            return result;
        } finally {
            src.release();
            out.release();
            from.release();
            to.release();
            if (transform != null) transform.release();
        }
    }

    private static double dist(Point a, Point b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    /** Turns a fresh photo (orig) into the cropped page image (base). */
    static void processNew(android.content.Context c, String id) throws java.io.IOException {
        Bitmap bmp = ImageUtil.decodeFile(PageStore.orig(c, id), 4000);
        if (bmp == null) throw new java.io.IOException("Could not read image");
        Bitmap out = bmp;
        if (AppSettings.autoCrop(c)) {
            float[] q = detect(bmp);
            if (q != null) {
                PageStore.saveQuad(c, id, q);
                out = warp(bmp, q);
            }
        }
        ImageUtil.saveJpeg(out, PageStore.base(c, id), AppSettings.jpegQuality(c));
        if (out != bmp) out.recycle();
        bmp.recycle();
    }
}
