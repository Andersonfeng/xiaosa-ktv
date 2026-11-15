package com.xiaosa.ktv;

/**
 * 极简 QR 码生成器（不引第三方库，APK 体积几乎不变）。
 *
 * 只做这件事：给定一段**纯 ASCII 文本**（这里是 http://192.168.1.23:8098 这种地址），
 * 生成二维码模块矩阵。算法按 ISO/IEC 18004（Model 2）实现，参数固定为
 * Byte 模式 + 纠错等级 M、版本 1~10 自动选择——地址最长也就几十个字符，
 * 版本 2~3 就够，不需要支持到 40。
 *
 * 为什么不用现成库：
 *  - APK 一直控制在 145KB 左右，引一个 zxing core（约 580KB）会让体积翻几倍；
 *  - 这里只需要"把一个短字符串变成黑白点阵"，用不到解码/识别那一套。
 *
 * 正确性校验：实现完成后用服务端依赖里的 qrcode(npm) 对同一输入生成矩阵逐点比对，
 * 版本 1~10 各取若干样本全部一致（见项目提交记录），不是"看着像"就发。
 *
 * 注意：这个类不依赖任何 Android API，可以用桌面 javac 单独编译/运行来验证。
 */
final class QrCode {

    /** 纠错等级 M 下各版本的总码字数（数据 + 纠错）。 */
    private static final int[] TOTAL_CODEWORDS = {
            26, 44, 70, 100, 134, 172, 196, 242, 292, 346
    };
    /** 纠错等级 M 下各版本的纠错块数量。 */
    private static final int[] EC_BLOCKS = {1, 1, 1, 2, 2, 4, 4, 4, 5, 5};
    /** 纠错等级 M 下各版本的纠错码字总数。 */
    private static final int[] EC_CODEWORDS = {10, 16, 26, 36, 48, 64, 72, 88, 110, 130};

    /** 纠错等级 M 在格式信息里的 2 位编码（L=01, M=00, Q=11, H=10）。 */
    private static final int EC_LEVEL_M_BITS = 0;
    private static final int MAX_VERSION = 10;

    final int version;
    final int size;
    final int mask;
    final boolean[][] modules;

    private QrCode(int version, int mask, boolean[][] modules) {
        this.version = version;
        this.size = modules.length;
        this.mask = mask;
        this.modules = modules;
    }

    /**
     * 把文本编码成二维码矩阵。
     *
     * @throws IllegalArgumentException 文本太长（超过版本 10 的能力，实测要 200+ 字符）
     *                                  或者含有非 ASCII 字符
     */
    static QrCode encode(String text) {
        byte[] bytes = toBytes(text);
        int version = pickVersion(bytes.length);
        if (version < 0) throw new IllegalArgumentException("文本太长，无法编码到 QR 版本 10");

        int[] codewords = buildCodewords(bytes, version);
        boolean[][] m = new boolean[sizeOf(version)][sizeOf(version)];
        boolean[][] reserved = new boolean[m.length][m.length];

        setupFinder(m, reserved);
        setupTiming(m, reserved);
        setupAlignment(m, reserved, version);
        // 先按 mask=0 写一遍格式信息，把这块区域标记成"已占用"，后面盖 mask 时跳过
        setupFormatInfo(m, reserved, EC_LEVEL_M_BITS, 0);
        if (version >= 7) setupVersionInfo(m, reserved, version);
        setupData(m, reserved, codewords);

        int bestMask = 0;
        int lowestPenalty = Integer.MAX_VALUE;
        for (int p = 0; p < 8; p++) {
            setupFormatInfo(m, reserved, EC_LEVEL_M_BITS, p);
            applyMask(m, reserved, p);
            int penalty = penaltyN1(m) + penaltyN2(m) + penaltyN3(m) + penaltyN4(m);
            applyMask(m, reserved, p); // 同一 mask 再异或一次 = 撤销
            if (penalty < lowestPenalty) {
                lowestPenalty = penalty;
                bestMask = p;
            }
        }

        applyMask(m, reserved, bestMask);
        setupFormatInfo(m, reserved, EC_LEVEL_M_BITS, bestMask);
        return new QrCode(version, bestMask, m);
    }

    // ------------------------------------------------------------------ 数据编码

    private static int sizeOf(int version) {
        return version * 4 + 17;
    }

    /** 选能装下载荷的最小版本；装不下返回 -1。 */
    private static int pickVersion(int byteLen) {
        for (int v = 1; v <= MAX_VERSION; v++) {
            int dataCodewords = TOTAL_CODEWORDS[v - 1] - EC_CODEWORDS[v - 1];
            int countBits = v <= 9 ? 8 : 16;
            int needBits = 4 + countBits + byteLen * 8;
            if (needBits <= dataCodewords * 8) return v;
        }
        return -1;
    }

    private static byte[] toBytes(String text) {
        // 只接受 ASCII：URL 里出现中文的概率极低，这里不为它增加编码复杂度
        byte[] out = new byte[text.length()];
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c > 0x7F) throw new IllegalArgumentException("只支持 ASCII 文本");
            out[i] = (byte) c;
        }
        return out;
    }

    /** Byte 模式 + 纠错 M：加模式位/字符计数/终止符/填充，再分块算纠错码并交织。 */
    private static int[] buildCodewords(byte[] bytes, int version) {
        int total = TOTAL_CODEWORDS[version - 1];
        int ecTotal = EC_CODEWORDS[version - 1];
        int dataTotal = total - ecTotal;
        int dataTotalBits = dataTotal * 8;

        BitBuf buf = new BitBuf(dataTotalBits + 8);
        buf.put(0x4, 4); // Byte 模式
        buf.put(bytes.length, version <= 9 ? 8 : 16);
        for (byte b : bytes) buf.put(b & 0xFF, 8);

        if (buf.length + 4 <= dataTotalBits) buf.put(0, 4); // 终止符最多 4 bit
        while (buf.length % 8 != 0) buf.putBit(0);
        int remaining = (dataTotalBits - buf.length) / 8;
        for (int i = 0; i < remaining; i++) buf.put(i % 2 == 0 ? 0xEC : 0x11, 8);

        return interleave(buf.toBytes(), version, total, dataTotal, ecTotal);
    }

    private static int[] interleave(byte[] data, int version, int total, int dataTotal, int ecTotal) {
        int blocks = EC_BLOCKS[version - 1];
        int blocksInGroup2 = total % blocks;
        int blocksInGroup1 = blocks - blocksInGroup2;
        int totalInGroup1 = total / blocks;
        int dataInGroup1 = dataTotal / blocks;
        int dataInGroup2 = dataInGroup1 + 1;
        int ecCount = totalInGroup1 - dataInGroup1;

        int[][] dc = new int[blocks][];
        int[][] ec = new int[blocks][];
        int offset = 0;
        int maxData = 0;
        for (int b = 0; b < blocks; b++) {
            int dataSize = b < blocksInGroup1 ? dataInGroup1 : dataInGroup2;
            dc[b] = new int[dataSize];
            for (int i = 0; i < dataSize; i++) dc[b][i] = data[offset + i] & 0xFF;
            ec[b] = reedSolomon(dc[b], ecCount);
            offset += dataSize;
            maxData = Math.max(maxData, dataSize);
        }

        int[] out = new int[total];
        int idx = 0;
        for (int i = 0; i < maxData; i++) {
            for (int b = 0; b < blocks; b++) {
                if (i < dc[b].length) out[idx++] = dc[b][i];
            }
        }
        for (int i = 0; i < ecCount; i++) {
            for (int b = 0; b < blocks; b++) out[idx++] = ec[b][i];
        }
        return out;
    }

    // ------------------------------------------------------------- Reed-Solomon

    private static final int[] GF_EXP = new int[512];
    private static final int[] GF_LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            GF_EXP[i] = x;
            GF_LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11D; // x^8 + x^4 + x^3 + x^2 + 1
        }
        for (int i = 255; i < 512; i++) GF_EXP[i] = GF_EXP[i - 255];
    }

    private static int gfMul(int a, int b) {
        if (a == 0 || b == 0) return 0;
        return GF_EXP[GF_LOG[a] + GF_LOG[b]];
    }

    /** 生成多项式 g(x) = (x - a^0)(x - a^1)...(x - a^(n-1))，返回长度为 n+1、首项为 1 的系数。 */
    private static int[] generator(int degree) {
        int[] g = new int[]{1};
        for (int i = 0; i < degree; i++) {
            int[] next = new int[g.length + 1];
            for (int j = 0; j < g.length; j++) {
                next[j] ^= g[j];
                next[j + 1] ^= gfMul(g[j], GF_EXP[i]);
            }
            g = next;
        }
        return g;
    }

    private static int[] reedSolomon(int[] data, int ecCount) {
        int[] gen = generator(ecCount);
        int[] rem = new int[ecCount];
        for (int value : data) {
            int factor = value ^ rem[0];
            System.arraycopy(rem, 1, rem, 0, ecCount - 1);
            rem[ecCount - 1] = 0;
            for (int i = 0; i < ecCount; i++) rem[i] ^= gfMul(gen[i + 1], factor);
        }
        return rem;
    }

    // ------------------------------------------------------------------ 矩阵构建

    private static void set(boolean[][] m, boolean[][] reserved, int row, int col, boolean dark, boolean isReserved) {
        if (row < 0 || col < 0 || row >= m.length || col >= m.length) return;
        m[row][col] = dark;
        if (isReserved) reserved[row][col] = true;
    }

    private static void setupFinder(boolean[][] m, boolean[][] reserved) {
        int size = m.length;
        int[][] pos = {{0, 0}, {0, size - 7}, {size - 7, 0}};
        for (int[] p : pos) {
            int row = p[0], col = p[1];
            for (int r = -1; r <= 7; r++) {
                for (int c = -1; c <= 7; c++) {
                    int rr = row + r, cc = col + c;
                    if (rr < 0 || cc < 0 || rr >= size || cc >= size) continue;
                    boolean dark = (r >= 0 && r <= 6 && (c == 0 || c == 6))
                            || (c >= 0 && c <= 6 && (r == 0 || r == 6))
                            || (r >= 2 && r <= 4 && c >= 2 && c <= 4);
                    set(m, reserved, rr, cc, dark, true);
                }
            }
        }
    }

    private static void setupTiming(boolean[][] m, boolean[][] reserved) {
        int size = m.length;
        for (int r = 8; r < size - 8; r++) {
            boolean dark = r % 2 == 0;
            set(m, reserved, r, 6, dark, true);
            set(m, reserved, 6, r, dark, true);
        }
    }

    private static int[] alignmentCoords(int version) {
        if (version == 1) return new int[0];
        int posCount = version / 7 + 2;
        int size = sizeOf(version);
        int intervals = size == 145 ? 26 : (int) Math.ceil((size - 13) / (2.0 * posCount - 2)) * 2;
        int[] pos = new int[posCount];
        pos[0] = size - 7;
        for (int i = 1; i < posCount - 1; i++) pos[i] = pos[i - 1] - intervals;
        pos[posCount - 1] = 6;
        // 反转成升序
        for (int i = 0, j = posCount - 1; i < j; i++, j--) {
            int t = pos[i];
            pos[i] = pos[j];
            pos[j] = t;
        }
        return pos;
    }

    private static void setupAlignment(boolean[][] m, boolean[][] reserved, int version) {
        int[] pos = alignmentCoords(version);
        int n = pos.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue;
                int row = pos[i], col = pos[j];
                for (int r = -2; r <= 2; r++) {
                    for (int c = -2; c <= 2; c++) {
                        boolean dark = r == -2 || r == 2 || c == -2 || c == 2 || (r == 0 && c == 0);
                        set(m, reserved, row + r, col + c, dark, true);
                    }
                }
            }
        }
    }

    private static int bchDigit(int data) {
        int digit = 0;
        while (data != 0) {
            digit++;
            data >>>= 1;
        }
        return digit;
    }

    /** 15 位格式信息：5 位数据 + 10 位 BCH，最后异或 0x5412。 */
    private static int formatBits(int ecLevelBits, int mask) {
        int data = (ecLevelBits << 3) | mask;
        int d = data << 10;
        int g = 0x537;
        while (bchDigit(d) - bchDigit(g) >= 0) d ^= (g << (bchDigit(d) - bchDigit(g)));
        return ((data << 10) | d) ^ 0x5412;
    }

    private static void setupFormatInfo(boolean[][] m, boolean[][] reserved, int ecLevelBits, int mask) {
        int size = m.length;
        int bits = formatBits(ecLevelBits, mask);
        for (int i = 0; i < 15; i++) {
            boolean dark = ((bits >> i) & 1) == 1;
            // 竖排
            if (i < 6) set(m, reserved, i, 8, dark, true);
            else if (i < 8) set(m, reserved, i + 1, 8, dark, true);
            else set(m, reserved, size - 15 + i, 8, dark, true);
            // 横排
            if (i < 8) set(m, reserved, 8, size - i - 1, dark, true);
            else if (i < 9) set(m, reserved, 8, 15 - i - 1 + 1, dark, true);
            else set(m, reserved, 8, 15 - i - 1, dark, true);
        }
        set(m, reserved, size - 8, 8, true, true); // 固定的暗模块
    }

    private static void setupVersionInfo(boolean[][] m, boolean[][] reserved, int version) {
        int size = m.length;
        int bits = versionBits(version);
        for (int i = 0; i < 18; i++) {
            int row = i / 3;
            int col = i % 3 + size - 8 - 3;
            boolean dark = ((bits >> i) & 1) == 1;
            set(m, reserved, row, col, dark, true);
            set(m, reserved, col, row, dark, true);
        }
    }

    private static int versionBits(int version) {
        int d = version << 12;
        int g = 0x1F25;
        while (bchDigit(d) - bchDigit(g) >= 0) d ^= (g << (bchDigit(d) - bchDigit(g)));
        return (version << 12) | d;
    }

    private static void setupData(boolean[][] m, boolean[][] reserved, int[] data) {
        int size = m.length;
        int inc = -1;
        int row = size - 1;
        int bitIndex = 7;
        int byteIndex = 0;

        for (int col = size - 1; col > 0; col -= 2) {
            if (col == 6) col--;
            while (true) {
                for (int c = 0; c < 2; c++) {
                    if (!reserved[row][col - c]) {
                        boolean dark = false;
                        if (byteIndex < data.length) {
                            dark = ((data[byteIndex] >>> bitIndex) & 1) == 1;
                        }
                        m[row][col - c] = dark;
                        bitIndex--;
                        if (bitIndex == -1) {
                            byteIndex++;
                            bitIndex = 7;
                        }
                    }
                }
                row += inc;
                if (row < 0 || row >= size) {
                    row -= inc;
                    inc = -inc;
                    break;
                }
            }
        }
    }

    private static boolean maskAt(int pattern, int i, int j) {
        switch (pattern) {
            case 0: return (i + j) % 2 == 0;
            case 1: return i % 2 == 0;
            case 2: return j % 3 == 0;
            case 3: return (i + j) % 3 == 0;
            case 4: return (i / 2 + j / 3) % 2 == 0;
            case 5: return (i * j) % 2 + (i * j) % 3 == 0;
            case 6: return ((i * j) % 2 + (i * j) % 3) % 2 == 0;
            default: return ((i * j) % 3 + (i + j) % 2) % 2 == 0;
        }
    }

    private static void applyMask(boolean[][] m, boolean[][] reserved, int pattern) {
        int size = m.length;
        for (int col = 0; col < size; col++) {
            for (int row = 0; row < size; row++) {
                if (reserved[row][col]) continue;
                if (maskAt(pattern, row, col)) m[row][col] = !m[row][col];
            }
        }
    }

    // ---------------------------------------------------------------- 掩码评分

    private static int penaltyN1(boolean[][] m) {
        int size = m.length;
        int points = 0;
        for (int row = 0; row < size; row++) {
            int sameCol = 0, sameRow = 0;
            Boolean lastCol = null, lastRow = null;
            for (int col = 0; col < size; col++) {
                boolean v = m[row][col];
                if (lastCol != null && v == lastCol) sameCol++;
                else {
                    if (sameCol >= 5) points += 3 + (sameCol - 5);
                    lastCol = v;
                    sameCol = 1;
                }
                boolean w = m[col][row];
                if (lastRow != null && w == lastRow) sameRow++;
                else {
                    if (sameRow >= 5) points += 3 + (sameRow - 5);
                    lastRow = w;
                    sameRow = 1;
                }
            }
            if (sameCol >= 5) points += 3 + (sameCol - 5);
            if (sameRow >= 5) points += 3 + (sameRow - 5);
        }
        return points;
    }

    private static int penaltyN2(boolean[][] m) {
        int size = m.length;
        int points = 0;
        for (int row = 0; row < size - 1; row++) {
            for (int col = 0; col < size - 1; col++) {
                int sum = (m[row][col] ? 1 : 0) + (m[row][col + 1] ? 1 : 0)
                        + (m[row + 1][col] ? 1 : 0) + (m[row + 1][col + 1] ? 1 : 0);
                if (sum == 4 || sum == 0) points++;
            }
        }
        return points * 3;
    }

    private static int penaltyN3(boolean[][] m) {
        int size = m.length;
        int points = 0;
        for (int row = 0; row < size; row++) {
            int bitsCol = 0, bitsRow = 0;
            for (int col = 0; col < size; col++) {
                bitsCol = ((bitsCol << 1) & 0x7FF) | (m[row][col] ? 1 : 0);
                if (col >= 10 && (bitsCol == 0x5D0 || bitsCol == 0x05D)) points++;
                bitsRow = ((bitsRow << 1) & 0x7FF) | (m[col][row] ? 1 : 0);
                if (col >= 10 && (bitsRow == 0x5D0 || bitsRow == 0x05D)) points++;
            }
        }
        return points * 40;
    }

    private static int penaltyN4(boolean[][] m) {
        int dark = 0;
        int total = m.length * m.length;
        for (boolean[] row : m) for (boolean v : row) if (v) dark++;
        int k = Math.abs((int) Math.ceil((dark * 100.0 / total) / 5) - 10);
        return k * 10;
    }

    // ------------------------------------------------------------------ 小工具

    /** 只够用的位缓冲：按位写入、按字节取出。 */
    private static final class BitBuf {
        private final byte[] bits;
        int length;

        BitBuf(int capacityBits) {
            bits = new byte[capacityBits];
        }

        void putBit(int bit) {
            if (length < bits.length) bits[length++] = (byte) (bit & 1);
        }

        void put(int value, int count) {
            for (int i = count - 1; i >= 0; i--) putBit((value >>> i) & 1);
        }

        byte[] toBytes() {
            byte[] out = new byte[(length + 7) / 8];
            for (int i = 0; i < length; i++) {
                if (bits[i] != 0) out[i / 8] |= (byte) (0x80 >>> (i % 8));
            }
            return out;
        }
    }
}
