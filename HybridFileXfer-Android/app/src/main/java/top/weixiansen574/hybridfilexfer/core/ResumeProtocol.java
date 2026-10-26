package top.weixiansen574.hybridfilexfer.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import top.weixiansen574.nio.DataByteChannel;

/**
 * 续传协商编解码（契约 §2.4）。两端共用，消息读写只允许出现在本类。
 *
 * <p>顺序（唯一，两端都不得变）：协商永远由<b>发送方先发起</b>，接收方被动应答：
 * <pre>
 * 发送方 writeQuery           接收方 readQuery
 * 接收方 writeReply    &lt;----  发送方 readReply
 * </pre>
 *
 * <p>协议层错误一律 {@link IOException}，沿用既有异常路径（→ onChannelError → 传输中止）。
 */
public final class ResumeProtocol {

    /** RESUME_QUERY 只读载体 */
    public static final class ResumeQuery {
        public final String taskKey;
        public final boolean crcEnabled;
        public final String senderRoot;
        public final String destRoot;
        public final List<ResumeTaskKey.Entry> entries;

        public ResumeQuery(String taskKey, boolean crcEnabled, String senderRoot, String destRoot,
                           List<ResumeTaskKey.Entry> entries) {
            this.taskKey = taskKey == null ? "" : taskKey;
            this.crcEnabled = crcEnabled;
            this.senderRoot = senderRoot == null ? "" : senderRoot;
            this.destRoot = destRoot == null ? "" : destRoot;
            this.entries = entries == null ? new ArrayList<ResumeTaskKey.Entry>() : entries;
        }
    }

    /** entryCount / replyCount 上限，防流错位造成的 OOM */
    private static final int MAX_ENTRIES = 1000000;
    /** blockCount 上限：4TB / 1MB */
    private static final int MAX_BLOCK_COUNT = 4194304;

    private ResumeProtocol() {
    }

    public static void writeQuery(DataByteChannel channel, String taskKey, boolean crcEnabled,
                                  String senderRoot, String destRoot,
                                  List<ResumeTaskKey.Entry> entries) throws IOException {
        int count = entries == null ? 0 : entries.size();
        channel.writeShort(ControllerIdentifiers.RESUME_QUERY);
        channel.writeInt(count);
        channel.writeUTF(taskKey == null ? "" : taskKey);
        channel.writeBoolean(crcEnabled);
        channel.writeUTF(senderRoot == null ? "" : senderRoot);
        channel.writeUTF(destRoot == null ? "" : destRoot);
        for (int i = 0; i < count; i++) {
            ResumeTaskKey.Entry entry = entries.get(i);
            channel.writeBoolean(entry.isFile);
            channel.writeUTF(entry.destPath);
            channel.writeLong(entry.totalSize);
            channel.writeLong(entry.lastModified);
        }
        channel.writeShort(ControllerIdentifiers.RESUME_PLAN_END);
    }

    public static ResumeQuery readQuery(DataByteChannel channel) throws IOException {
        short message = channel.readShort();
        if (message != ControllerIdentifiers.RESUME_QUERY) {
            throw new IOException("unexpected control message: " + message);
        }
        int entryCount = channel.readInt();
        if (entryCount < 0 || entryCount > MAX_ENTRIES) {
            throw new IOException("bad entryCount: " + entryCount);
        }
        String taskKey = channel.readUTF();
        boolean crcEnabled = channel.readBoolean();
        String senderRoot = channel.readUTF();
        String destRoot = channel.readUTF();
        List<ResumeTaskKey.Entry> entries = new ArrayList<>(entryCount);
        for (int i = 0; i < entryCount; i++) {
            boolean isFile = channel.readBoolean();
            String destPath = channel.readUTF();
            long totalSize = channel.readLong();
            long lastModified = channel.readLong();
            entries.add(new ResumeTaskKey.Entry(isFile, destPath, totalSize, lastModified));
        }
        short end = channel.readShort();
        if (end != ControllerIdentifiers.RESUME_PLAN_END) {
            throw new IOException("resume plan not terminated");
        }
        return new ResumeQuery(taskKey, crcEnabled, senderRoot, destRoot, entries);
    }

    /** 接收方写出回执（只用到 entry 的 localPath / blockCount / bitmap 三个字段） */
    public static void writeReply(DataByteChannel channel, List<ResumeEntry> entries) throws IOException {
        int count = entries == null ? 0 : entries.size();
        channel.writeShort(ControllerIdentifiers.RESUME_REPLY);
        channel.writeInt(count);
        for (int i = 0; i < count; i++) {
            ResumeEntry entry = entries.get(i);
            channel.writeUTF(entry.localPath);
            channel.writeInt(entry.blockCount);
            byte[] bitmap = entry.bitmap.toBytes();
            channel.writeInt(bitmap.length);
            channel.writeFully(bitmap);
        }
    }

    /** 发送方读出回执：localPath -> 已完成位图，可直接构造 {@link ResumeSkipSet} */
    public static Map<String, BlockBitmap> readReply(DataByteChannel channel) throws IOException {
        short message = channel.readShort();
        if (message != ControllerIdentifiers.RESUME_REPLY) {
            throw new IOException("unexpected control message: " + message);
        }
        int replyCount = channel.readInt();
        if (replyCount < 0 || replyCount > MAX_ENTRIES) {
            throw new IOException("bad replyCount: " + replyCount);
        }
        Map<String, BlockBitmap> completed = new HashMap<>();
        for (int i = 0; i < replyCount; i++) {
            String localPath = channel.readUTF();
            int blockCount = channel.readInt();
            if (blockCount < 1 || blockCount > MAX_BLOCK_COUNT) {
                throw new IOException("bad blockCount: " + blockCount);
            }
            int byteLen = channel.readInt();
            if (byteLen != BlockBitmap.byteLength(blockCount)) {
                throw new IOException("bad bitmap length: " + byteLen
                        + " expected " + BlockBitmap.byteLength(blockCount));
            }
            byte[] bitmap = new byte[byteLen];
            channel.readFully(bitmap);
            completed.put(localPath, BlockBitmap.fromBytes(blockCount, bitmap));
        }
        return completed;
    }
}
