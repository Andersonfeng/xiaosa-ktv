package com.xiaosa.ktv;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * 把 {@link QrCode} 的模块矩阵画成 Bitmap。
 *
 * 二维码四周必须留至少 4 个模块宽的静区（quie zone），否则很多手机扫不出来；
 * 这里统一留 4 个模块，并且用整数倍缩放，保证每个模块都是完整像素块、边缘不发虚。
 */
final class QrBitmap {
    private QrBitmap() {}

    private static final int QUIET_MODULES = 4;

    /**
     * @param qr      模块矩阵
     * @param sizePx  期望边长（像素），实际会取整到"每个模块整数像素"的尺寸
     */
    static Bitmap render(QrCode qr, int sizePx) {
        int modules = qr.size + QUIET_MODULES * 2;
        int modulePx = Math.max(1, sizePx / modules);
        int px = modulePx * modules;

        int[] pixels = new int[px * px];
        // 先铺满白色（含静区）
        java.util.Arrays.fill(pixels, Color.WHITE);

        int offset = QUIET_MODULES * modulePx;
        for (int row = 0; row < qr.size; row++) {
            for (int col = 0; col < qr.size; col++) {
                if (!qr.modules[row][col]) continue;
                int top = offset + row * modulePx;
                int left = offset + col * modulePx;
                for (int y = 0; y < modulePx; y++) {
                    int base = (top + y) * px + left;
                    for (int x = 0; x < modulePx; x++) pixels[base + x] = Color.BLACK;
                }
            }
        }

        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        bmp.setPixels(pixels, 0, px, 0, 0, px, px);
        return bmp;
    }
}
