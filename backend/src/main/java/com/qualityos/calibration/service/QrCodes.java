package com.qualityos.calibration.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

public final class QrCodes {

    private static final int QUIET_ZONE = 4;

    private QrCodes() {
    }

    public static String svg(String content) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0);
            int size = matrix.getWidth() + 2 * QUIET_ZONE;
            StringBuilder path = new StringBuilder();
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    if (matrix.get(x, y)) {
                        path.append("M%d %dh1v1h-1z".formatted(x + QUIET_ZONE, y + QUIET_ZONE));
                    }
                }
            }
            return """
                    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %1$d %1$d" shape-rendering="crispEdges">\
                    <rect width="%1$d" height="%1$d" fill="#fff"/><path d="%2$s" fill="#000"/></svg>"""
                    .formatted(size, path);
        } catch (WriterException ex) {
            throw new IllegalArgumentException("This cannot be held in a QR code.", ex);
        }
    }
}
