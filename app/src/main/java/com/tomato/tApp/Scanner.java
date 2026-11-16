package com.tomato.tApp;

import android.content.Context;
import android.graphics.Bitmap;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.RotatedRect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the sheet of paper in a photo and flattens it (perspective correction).
 * Corners are always passed around as fractions of the image (0..1), in the order
 * top-left, top-right, bottom-right, bottom-left: x0,y0,x1,y1,x2,y2,x3,y3.
 *
 * Detection tries several different methods on a cleaned-up copy of the image
 * (text is wiped out first so only the page outline remains) and keeps the
 * best-looking rectangle among all of them.
 */
final class Scanner {

    private static boolean ready;

    /** Last detection problem (shown in the camera so failures are never silent). */
    static volatile String lastError;

    private Scanner() {
    }

    private static synchronized boolean init() {
        if (!ready) {
            try {
                ready = OpenCVLoader.initLocal();
            } catch (Throwable t) {
                ready = false;
            }
            if (!ready) lastError = "OpenCV could not load";
        }
        return ready;
    }

    private static final class Cand {
        final Point[] pts;
        final double score;

        Cand(Point[] pts, double score) {
            this.pts = pts;
            this.score = score;
        }
    }

    /** Detect on a full-colour bitmap. Returns the page corners, or null if no clear page was found. */
    static float[] detect(Bitmap bmp) {
        if (!init()) return null;
        Mat rgba = new Mat();
        Mat gray = new Mat();
        try {
            Utils.bitmapToMat(bmp, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            return detectGray(gray);
        } catch (Throwable t) {
            return null;
        } finally {
            rgba.release();
            gray.release();
        }
    }

    /** Detect on a single-channel (grey) image. Also used for the live camera preview. */
    static float[] detectGray(Mat src) {
        if (!init()) return null;
        Mat gray = new Mat();
        Mat clean = new Mat();
        Mat blur = new Mat();
        Mat k9 = null;
        try {
            double scale = Math.min(1.0, 800.0 / Math.max(src.cols(), src.rows()));
            if (scale < 1.0) {
                Imgproc.resize(src, gray, new Size(), scale, scale, Imgproc.INTER_AREA);
            } else {
                src.copyTo(gray);
            }

            // Wipe out dark text and fine texture; keeps the page outline.
            k9 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(9, 9));
            Imgproc.morphologyEx(gray, clean, Imgproc.MORPH_CLOSE, k9);
            Imgproc.GaussianBlur(clean, blur, new Size(5, 5), 0);

            int w = blur.cols();
            int h = blur.rows();
            double med = median(blur);
            double otsu = otsuLevel(blur);

            Cand best = null;
            // 1) edge based
            best = pick(best, fromCanny(blur, Math.max(10, 0.66 * med), Math.max(30, 1.33 * med), w, h));
            best = pick(best, fromCanny(blur, Math.max(8, 0.33 * med), Math.max(25, 0.66 * med), w, h));
            best = pick(best, fromCanny(blur, 30, 90, w, h));
            // 2) brightness based: a white page is usually brighter than what's under it
            for (double f : new double[]{0.85, 1.0, 1.15}) {
                best = pick(best, fromThreshold(blur, otsu * f, false, w, h));
            }
            best = pick(best, fromThreshold(blur, otsu, true, w, h));

            if (best == null) {
                lastError = null; // working fine, just no page in view
                return null;
            }

            float[] q = new float[8];
            for (int i = 0; i < 4; i++) {
                q[2 * i] = clamp01((float) (best.pts[i].x / w));
                q[2 * i + 1] = clamp01((float) (best.pts[i].y / h));
            }
            lastError = null;
            return q;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            return null;
        } finally {
            gray.release();
            clean.release();
            blur.release();
            if (k9 != null) k9.release();
        }
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static double otsuLevel(Mat blur) {
        Mat tmp = new Mat();
        try {
            return Imgproc.threshold(blur, tmp, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
        } finally {
            tmp.release();
        }
    }

    private static Cand pick(Cand a, Cand b) {
        if (b == null) return a;
        if (a == null || b.score > a.score) return b;
        return a;
    }

    private static double median(Mat m) {
        int n = (int) m.total();
        byte[] d = new byte[n];
        m.get(0, 0, d);
        int[] hist = new int[256];
        for (byte b : d) hist[b & 0xFF]++;
        int acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            if (acc >= n / 2) return i;
        }
        return 128;
    }

    private static Cand fromCanny(Mat blur, double lo, double hi, int w, int h) {
        Mat edges = new Mat();
        Mat k3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
        Mat k5 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 5));
        try {
            Imgproc.Canny(blur, edges, lo, hi);
            Imgproc.dilate(edges, edges, k3);
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, k5); // joins broken edges
            return bestFromBinary(edges, w, h);
        } finally {
            edges.release();
            k3.release();
            k5.release();
        }
    }

    private static Cand fromThreshold(Mat blur, double level, boolean inverse, int w, int h) {
        Mat bw = new Mat();
        Mat k7 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(7, 7));
        try {
            Imgproc.threshold(blur, bw, level, 255,
                    inverse ? Imgproc.THRESH_BINARY_INV : Imgproc.THRESH_BINARY);
            Imgproc.morphologyEx(bw, bw, Imgproc.MORPH_CLOSE, k7);
            Imgproc.morphologyEx(bw, bw, Imgproc.MORPH_OPEN, k7);
            return bestFromBinary(bw, w, h);
        } finally {
            bw.release();
            k7.release();
        }
    }

    private static Cand bestFromBinary(Mat binary, int w, int h) {
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Mat work = binary.clone();
        Cand best = null;
        try {
            Imgproc.findContours(work, contours, hierarchy,
                    Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
            for (MatOfPoint c : contours) best = pick(best, evaluate(c, w, h));
        } finally {
            for (MatOfPoint c : contours) c.release();
            hierarchy.release();
            work.release();
        }
        return best;
    }

    /** Turns a contour into a 4-corner candidate and scores how page-like it is. */
    private static Cand evaluate(MatOfPoint contour, int w, int h) {
        double imgArea = (double) w * h;
        double area = Imgproc.contourArea(contour);
        if (area < imgArea * 0.08) return null;

        MatOfInt hullIdx = new MatOfInt();
        MatOfPoint2f hull2f = null;
        try {
            Imgproc.convexHull(contour, hullIdx);
            Point[] pts = contour.toArray();
            int[] idx = hullIdx.toArray();
            Point[] hp = new Point[idx.length];
            for (int i = 0; i < idx.length; i++) hp[i] = pts[idx[i]];
            hull2f = new MatOfPoint2f(hp);

            // Best case: the outline really is a four-cornered shape.
            double peri = Imgproc.arcLength(hull2f, true);
            Point[] quad = null;
            for (double eps : new double[]{0.02, 0.03, 0.045, 0.06, 0.08}) {
                MatOfPoint2f ap = new MatOfPoint2f();
                Imgproc.approxPolyDP(hull2f, ap, eps * peri, true);
                boolean four = ap.total() == 4;
                if (four) quad = ap.toArray();
                ap.release();
                if (four) break;
            }
            if (quad != null) {
                quad = order(quad);
                if (anglesOk(quad)) {
                    double quadArea = polygonArea(quad);
                    if (quadArea >= imgArea * 0.08 && quadArea <= imgArea * 0.985) {
                        double fit = Math.min(1.0, area / quadArea);
                        return new Cand(quad, (quadArea / imgArea) * fit);
                    }
                }
            }

            // Otherwise: a tilted rectangle around the blob, if the blob is roughly rectangular.
            RotatedRect rr = Imgproc.minAreaRect(hull2f);
            double rectArea = rr.size.width * rr.size.height;
            if (rectArea < imgArea * 0.08 || rectArea > imgArea * 0.985) return null;
            double fit = Imgproc.contourArea(hull2f) / rectArea;
            if (fit < 0.8) return null;
            Point[] corners = new Point[4];
            rr.points(corners);
            return new Cand(order(corners), (rectArea / imgArea) * Math.min(1.0, fit) * 0.7);
        } finally {
            hullIdx.release();
            if (hull2f != null) hull2f.release();
        }
    }

    private static boolean anglesOk(Point[] q) {
        for (int i = 0; i < 4; i++) {
            Point a = q[(i + 3) % 4];
            Point b = q[i];
            Point c = q[(i + 1) % 4];
            double v1x = a.x - b.x, v1y = a.y - b.y;
            double v2x = c.x - b.x, v2y = c.y - b.y;
            double cos = (v1x * v2x + v1y * v2y)
                    / (Math.hypot(v1x, v1y) * Math.hypot(v2x, v2y) + 1e-9);
            double ang = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
            if (ang < 50 || ang > 130) return false;
        }
        return true;
    }

    private static double polygonArea(Point[] p) {
        double s = 0;
        for (int i = 0; i < p.length; i++) {
            Point a = p[i];
            Point b = p[(i + 1) % p.length];
            s += a.x * b.y - b.x * a.y;
        }
        return Math.abs(s) / 2.0;
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

    /**
     * Turns a fresh photo (orig) into the cropped page image (base).
     * Photos from the built-in camera already carry the corners the user saw on screen.
     */
    static void processNew(Context c, String id) throws IOException {
        Bitmap bmp = ImageUtil.decodeFile(PageStore.orig(c, id), 4000);
        if (bmp == null) throw new IOException("Could not read image");
        Bitmap out = bmp;
        if (AppSettings.autoCrop(c)) {
            float[] q = PageStore.getQuad(c, id);
            if (q == null) q = detect(bmp);
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
