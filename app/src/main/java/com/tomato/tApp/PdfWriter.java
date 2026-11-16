package com.tomato.tApp;

import android.graphics.BitmapFactory;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Minimal multi-page PDF writer. Each page's JPEG is embedded as-is (no re-compression),
 * so the PDF is exactly as sharp as the scan, and files stay small.
 */
final class PdfWriter {

    private static final float MAX_W = 595.28f; // A4 in points
    private static final float MAX_H = 841.89f;

    private PdfWriter() {
    }

    private static final class Counter extends OutputStream {
        private final OutputStream out;
        long count = 0;

        Counter(OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        void ascii(String s) throws IOException {
            write(s.getBytes(StandardCharsets.ISO_8859_1));
        }
    }

    private static String num(float v) {
        return String.format(Locale.US, "%.2f", v);
    }

    static void write(List<File> jpegs, OutputStream target) throws IOException {
        int n = jpegs.size();
        if (n == 0) throw new IOException("No pages");

        Counter out = new Counter(new BufferedOutputStream(target, 64 * 1024));
        long[] offs = new long[3 + 3 * n];

        out.ascii("%PDF-1.4\n");
        out.write(new byte[]{'%', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3, '\n'});

        offs[1] = out.count;
        out.ascii("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");

        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < n; i++) kids.append(3 + 3 * i).append(" 0 R ");
        offs[2] = out.count;
        out.ascii("2 0 obj\n<< /Type /Pages /Count " + n + " /Kids [" + kids + "] >>\nendobj\n");

        byte[] buf = new byte[64 * 1024];
        for (int i = 0; i < n; i++) {
            File f = jpegs.get(i);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int w = o.outWidth;
            int h = o.outHeight;
            if (w <= 0 || h <= 0) throw new IOException("Unreadable page " + (i + 1));

            float s = Math.min(MAX_W / w, MAX_H / h);
            float pw = w * s;
            float ph = h * s;

            int pageObj = 3 + 3 * i;
            int contObj = 4 + 3 * i;
            int imgObj = 5 + 3 * i;

            offs[pageObj] = out.count;
            out.ascii(pageObj + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 "
                    + num(pw) + " " + num(ph) + "] /Resources << /ProcSet [/PDF /ImageC] "
                    + "/XObject << /Im0 " + imgObj + " 0 R >> >> /Contents " + contObj
                    + " 0 R >>\nendobj\n");

            String content = "q " + num(pw) + " 0 0 " + num(ph) + " 0 0 cm /Im0 Do Q\n";
            offs[contObj] = out.count;
            out.ascii(contObj + " 0 obj\n<< /Length " + content.length()
                    + " >>\nstream\n" + content + "endstream\nendobj\n");

            offs[imgObj] = out.count;
            out.ascii(imgObj + " 0 obj\n<< /Type /XObject /Subtype /Image /Width " + w
                    + " /Height " + h + " /ColorSpace /DeviceRGB /BitsPerComponent 8"
                    + " /Filter /DCTDecode /Length " + f.length() + " >>\nstream\n");
            try (InputStream in = new FileInputStream(f)) {
                int r;
                while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
            }
            out.ascii("\nendstream\nendobj\n");
        }

        int total = 3 + 3 * n;
        long xref = out.count;
        out.ascii("xref\n0 " + total + "\n");
        out.ascii("0000000000 65535 f \n");
        for (int i = 1; i < total; i++) {
            out.ascii(String.format(Locale.US, "%010d 00000 n \n", offs[i]));
        }
        out.ascii("trailer\n<< /Size " + total + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        out.flush();
    }
}
