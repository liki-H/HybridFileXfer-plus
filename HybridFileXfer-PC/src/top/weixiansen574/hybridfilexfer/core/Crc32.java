package top.weixiansen574.hybridfilexfer.core;

import java.nio.ByteBuffer;

/**
 * CRC32（zlib 标准：多项式 0xEDB88320、反射、初值与终值取反），结果与 JDK 标准 CRC32 实现一致。
 *
 * <p>为什么要自己实现（契约 §4.3）：
 * <ul>
 *     <li>标准库的 {@code update(ByteBuffer)} 重载是 Java 9 API，Android API 24 上没有；</li>
 *     <li>Android 侧的缓冲区块是 JNI 直接内存（native direct buffer），不能 {@code array()}；</li>
 * </ul>
 * 因此这里用<b>绝对索引</b>读取，不改变 {@code buffer.position()/limit()}。
 */
public final class Crc32 {

    private static final int[] TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int k = 0; k < 8; k++) {
                c = ((c & 1) != 0) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
            }
            TABLE[i] = c;
        }
    }

    private Crc32() {
    }

    /**
     * 计算 {@code buf[offset, offset+length)} 的 CRC32。
     *
     * @param buf    缓冲（可用绝对索引访问，位置与上限不变）
     * @param offset 起始偏移
     * @param length 字节数
     */
    public static int compute(ByteBuffer buf, int offset, int length) {
        if (buf == null || length <= 0) {
            return 0;
        }
        int crc = 0xFFFFFFFF;
        for (int i = 0; i < length; i++) {
            int b = buf.get(offset + i) & 0xFF;
            crc = TABLE[(crc ^ b) & 0xFF] ^ (crc >>> 8);
        }
        return crc ^ 0xFFFFFFFF;
    }

    /** 计算整个数组的 CRC32（测试与工具用） */
    public static int compute(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return 0;
        }
        return compute(ByteBuffer.wrap(bytes), 0, bytes.length);
    }
}
