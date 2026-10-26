package top.weixiansen574.hybridfilexfer.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 一个传输会话的续传清单会话（接收方持有，契约 §3.4 / §3.5）。
 *
 * <p>不变量：
 * <ol>
 *     <li>位图只由单写线程 {@link WriteFileCall} 修改；{@link #abortFile(String)} 例外（CRC 失败路径，
 *     由 {@link ReceiveFileCall} 调用），因此所有公开方法都 {@code synchronized}；</li>
 *     <li><b>只擦不增</b>：{@link #abortFile(String)} 只删除该文件的行，不触碰同任务其它文件；</li>
 *     <li>落盘前调用方必须先 {@code force} 数据（见 {@link WriteFileCall}）；</li>
 *     <li>store 为 null 时全程 no-op（降级为"无续传"）。</li>
 * </ol>
 */
public final class ResumeSession {

    /** 每 32 块落盘一次（≈32MB） */
    public static final int FLUSH_BLOCK_INTERVAL = 32;
    /** 清单过期时间：7 天 */
    public static final long RETENTION_MS = 7L * 24 * 3600 * 1000;

    private final String taskKey;
    private final ResumeStore store;
    private final Map<String, Row> rows = new HashMap<>();
    /** 任务级字段：发送方源根目录（协商 query 的 senderRoot），同一任务所有行相同 */
    private String remoteRoot = "";
    private boolean purged;

    /**
     * @param taskKey 本端（接收方）自己算出的任务键
     * @param store   可为 null → 全程 no-op
     */
    public ResumeSession(String taskKey, ResumeStore store) {
        this.taskKey = taskKey == null ? "" : taskKey;
        this.store = store;
    }

    public String taskKey() {
        return taskKey;
    }

    /** 设置任务级 remotePath（协商阶段由 query 的 senderRoot 填入，仅用于清单诊断字段） */
    public synchronized void setRemoteRoot(String remoteRoot) {
        this.remoteRoot = remoteRoot == null ? "" : remoteRoot;
    }

    /** 读取已存在的行（同时装入内存行缓存）；无 store 时返回空表 */
    public synchronized List<ResumeEntry> loadExisting() {
        List<ResumeEntry> result = new ArrayList<>();
        if (store == null) {
            return result;
        }
        List<ResumeEntry> stored;
        try {
            stored = store.loadTask(taskKey);
        } catch (Throwable th) {
            th.printStackTrace();
            return result;
        }
        if (stored == null) {
            return result;
        }
        for (int i = 0; i < stored.size(); i++) {
            ResumeEntry entry = stored.get(i);
            if (entry == null) {
                continue;
            }
            rows.put(entry.localPath, new Row(entry));
            result.add(entry);
        }
        return result;
    }

    /** 每个会话最多清理一次过期行 */
    public synchronized void purgeExpiredIfNeeded(long nowMillis, long retentionMillis) {
        if (store == null || purged) {
            return;
        }
        purged = true;
        try {
            store.purgeExpired(nowMillis, retentionMillis);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /**
     * 一个块成功写盘后调用（只由单写线程调用）。
     *
     * <p>blockCount 与已存在行不一致 → 视为源文件已变，清空位图重建。
     */
    public synchronized void onBlockWritten(String localPath, long totalSize, long lastModified,
                                            int blockIndex, int blockCount) {
        if (store == null || localPath == null) {
            return;
        }
        Row row = rows.get(localPath);
        if (row == null || row.blockCount != blockCount) {
            row = new Row(new ResumeEntry(taskKey, 0, remoteRoot, localPath, totalSize, lastModified,
                    blockCount, new BlockBitmap(blockCount), false, System.currentTimeMillis()));
            rows.put(localPath, row);
        }
        row.totalSize = totalSize;
        row.lastModified = lastModified;
        row.bitmap.setCompleted(blockIndex, true);
        row.completed = row.bitmap.isAllCompleted();
        row.updatedAt = System.currentTimeMillis();
        row.dirty = true;
    }

    /** 整文件标记未完成（删除该行）+ 立即落盘 */
    public synchronized void abortFile(String localPath) {
        if (store == null || localPath == null) {
            return;
        }
        rows.remove(localPath);
        try {
            store.removeEntry(taskKey, localPath);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /** 脏行落盘（调用方负责先 force 数据） */
    public synchronized void flush() {
        if (store == null) {
            return;
        }
        List<Row> dirtyRows = new ArrayList<>();
        Iterator<Map.Entry<String, Row>> iterator = rows.entrySet().iterator();
        while (iterator.hasNext()) {
            Row row = iterator.next().getValue();
            if (row.dirty) {
                dirtyRows.add(row);
            }
        }
        for (int i = 0; i < dirtyRows.size(); i++) {
            Row row = dirtyRows.get(i);
            try {
                store.saveEntry(row.toEntry(taskKey, remoteRoot));
                row.dirty = false;
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
    }

    /** 会话成功完成：删除整个任务的清单 */
    public synchronized void markTaskCompleted() {
        rows.clear();
        if (store == null) {
            return;
        }
        try {
            store.removeTask(taskKey);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /** 内存行（可变） */
    private static final class Row {
        final String localPath;
        long totalSize;
        long lastModified;
        final int blockCount;
        final BlockBitmap bitmap;
        boolean completed;
        long updatedAt;
        boolean dirty;

        Row(ResumeEntry entry) {
            this.localPath = entry.localPath;
            this.totalSize = entry.totalSize;
            this.lastModified = entry.lastModified;
            this.blockCount = entry.blockCount;
            this.bitmap = entry.bitmap;
            this.completed = entry.completed;
            this.updatedAt = entry.updatedAt;
            this.dirty = false;
        }

        ResumeEntry toEntry(String taskKey, String remoteRoot) {
            return new ResumeEntry(taskKey, 0, remoteRoot, localPath,
                    totalSize, lastModified, blockCount, bitmap, completed, updatedAt);
        }
    }
}
