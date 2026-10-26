package top.weixiansen574.hybridfilexfer.core;

/**
 * 续传清单的一行（契约 §3.1）。全字段 final，构造即校验。
 *
 * <p>字段语义只在 {@link ResumeStore} 的实现里映射成存储形态：
 * Android 表 {@code resume_manifest} 用 snake_case 列名，PC 用 camelCase JSON 键。
 */
public final class ResumeEntry {

    public final String taskKey;
    /** 相对于清单持有设备：0 = 本机为接收方（写入方）。当前只会出现 0。 */
    public final int direction;
    /** 任务级：发送方源根目录（协商 query 的 senderRoot）。同一任务所有行相同 */
    public final String remotePath;
    /** 文件级：接收端绝对路径（= 块里的 path = 跳过集键） */
    public final String localPath;
    /** 文件级：源文件总长（来自块头） */
    public final long totalSize;
    /** 文件级：源文件 mtime（来自块头） */
    public final long lastModified;
    /** 文件级：规范块数（{@link FileBlock#calcBlockCount(long)}） */
    public final int blockCount;
    public final BlockBitmap bitmap;
    public final boolean completed;
    public final long updatedAt;

    public ResumeEntry(String taskKey, int direction, String remotePath, String localPath,
                       long totalSize, long lastModified, int blockCount,
                       BlockBitmap bitmap, boolean completed, long updatedAt) {
        if (taskKey == null) {
            throw new IllegalArgumentException("taskKey == null");
        }
        if (remotePath == null) {
            throw new IllegalArgumentException("remotePath == null");
        }
        if (localPath == null) {
            throw new IllegalArgumentException("localPath == null");
        }
        if (bitmap == null) {
            throw new IllegalArgumentException("bitmap == null");
        }
        this.taskKey = taskKey;
        this.direction = direction;
        this.remotePath = remotePath;
        this.localPath = localPath;
        this.totalSize = totalSize;
        this.lastModified = lastModified;
        this.blockCount = blockCount;
        this.bitmap = bitmap;
        this.completed = completed;
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return "ResumeEntry{localPath='" + localPath + "', totalSize=" + totalSize
                + ", blockCount=" + blockCount + ", completed=" + completed + "}";
    }
}
