package top.weixiansen574.hybridfilexfer.core;

/**
 * block 完成位图。
 *
 * <p>字节布局（契约 §3.1 / §5.1）：长度 {@code byteLength(blockCount) = (blockCount + 7) / 8}；
 * <b>bit i 位于 {@code bytes[i >>> 3]} 的 {@code 1 << (i & 7)}（LSB 优先）</b>；未使用的填充位必须为 0。
 *
 * <p>本类是唯一的位置位序定义处，两端（Android / PC）共用同一份源码。
 */
public final class BlockBitmap {

    private final int blockCount;
    private final byte[] bytes;

    /** 位图字节长度：blockCount <= 0 时为 0 */
    public static int byteLength(int blockCount) {
        if (blockCount <= 0) {
            return 0;
        }
        return (blockCount + 7) / 8;
    }

    /** 新建全未完成（零填充）的位图 */
    public BlockBitmap(int blockCount) {
        this.blockCount = Math.max(blockCount, 0);
        this.bytes = new byte[byteLength(this.blockCount)];
    }

    private BlockBitmap(int blockCount, byte[] bytes) {
        this.blockCount = blockCount;
        this.bytes = bytes;
    }

    public int blockCount() {
        return blockCount;
    }

    public int byteLength() {
        return bytes.length;
    }

    /** 越界返回 false */
    public boolean isCompleted(int blockIndex) {
        if (blockIndex < 0 || blockIndex >= blockCount) {
            return false;
        }
        return (bytes[blockIndex >>> 3] & (1 << (blockIndex & 7))) != 0;
    }

    /** 越界忽略 */
    public void setCompleted(int blockIndex, boolean completed) {
        if (blockIndex < 0 || blockIndex >= blockCount) {
            return;
        }
        int byteIndex = blockIndex >>> 3;
        int mask = 1 << (blockIndex & 7);
        if (completed) {
            bytes[byteIndex] = (byte) (bytes[byteIndex] | mask);
        } else {
            bytes[byteIndex] = (byte) (bytes[byteIndex] & ~mask);
        }
    }

    /** 至少 1 位已完成 */
    public boolean hasCompleted() {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] != 0) {
                return true;
            }
        }
        return false;
    }

    /** 是否全部完成（只看有效位，填充位不影响） */
    public boolean isAllCompleted() {
        if (blockCount <= 0) {
            return false;
        }
        for (int i = 0; i < blockCount; i++) {
            if (!isCompleted(i)) {
                return false;
            }
        }
        return true;
    }

    /** 返回拷贝，长度 = byteLength() */
    public byte[] toBytes() {
        byte[] copy = new byte[bytes.length];
        System.arraycopy(bytes, 0, copy, 0, bytes.length);
        return copy;
    }

    /**
     * 从字节构造。
     *
     * @throws IllegalArgumentException 长度与 byteLength(blockCount) 不符或 bytes 为 null
     */
    public static BlockBitmap fromBytes(int blockCount, byte[] bytes) {
        int expected = byteLength(blockCount);
        if (bytes == null || bytes.length != expected) {
            throw new IllegalArgumentException("bad bitmap length: "
                    + (bytes == null ? -1 : bytes.length) + " expected " + expected);
        }
        byte[] copy = new byte[expected];
        System.arraycopy(bytes, 0, copy, 0, expected);
        // 清除未使用的高位填充位，保证序列化确定性
        int usedBits = blockCount & 7;
        if (usedBits != 0 && copy.length > 0) {
            int mask = (1 << usedBits) - 1;
            copy[copy.length - 1] = (byte) (copy[copy.length - 1] & mask);
        }
        return new BlockBitmap(blockCount, copy);
    }

    @Override
    public String toString() {
        return "BlockBitmap{blockCount=" + blockCount + ", bytes=" + bytes.length + "}";
    }
}
