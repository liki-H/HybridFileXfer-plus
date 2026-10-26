package top.weixiansen574.hybridfilexfer.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

/**
 * 任务指纹（taskKey）：如何稳定识别"同一批任务"（契约 §2.5）。
 *
 * <p>定义输入：一次传输的<b>顶层选择集</b>（{@code sendFiles()} 的 files 参数），不含递归展开的子文件。
 *
 * <p>为什么这样定：
 * <ul>
 *     <li>只取顶层选择集：两端都能在不遍历整棵树的前提下算出同一个键；逐文件状态才是续传依据，
 *     树内新增/删除文件不会误命中（新文件没有位图 → 全量重传）；</li>
 *     <li>目录的 size/mtime 不参与：目录 mtime 会因子项变动而变，若参与则"续传几乎永远失效"；</li>
 *     <li>顶层文件的 size+mtime 参与：顶层文件被改写 → 键变 → 旧清单自然作废（保守且安全）；</li>
 *     <li>双端各算一次并比对：发送方写在 wire 上，接收方用收到条目重算，实现一旦分叉立刻暴露。</li>
 * </ul>
 */
public final class ResumeTaskKey {

    public static final String KEY_PREFIX = "HFX-RESUME-V1";
    /** 取 SHA-256 前 16 字节 → 32 位小写 hex */
    public static final int KEY_BYTES = 16;

    /** 顶部选择集的一项（协商 query 在 wire 上的条目） */
    public static final class Entry {
        public final boolean isFile;
        public final String destPath;
        public final long totalSize;
        public final long lastModified;

        public Entry(boolean isFile, String destPath, long totalSize, long lastModified) {
            this.isFile = isFile;
            this.destPath = destPath == null ? "" : destPath;
            this.totalSize = totalSize;
            this.lastModified = lastModified;
        }

        @Override
        public String toString() {
            return "Entry{isFile=" + isFile + ", destPath='" + destPath + "', totalSize=" + totalSize
                    + ", lastModified=" + lastModified + "}";
        }
    }

    private ResumeTaskKey() {
    }

    /**
     * 由顶层选择集生成规范化条目列表：按 destPath 升序（{@link String#compareTo}）、相同 path 去重。
     */
    public static List<Entry> fromSelection(List<RemoteFile> files, Directory localDir, Directory remoteDir) {
        List<Entry> entries = new ArrayList<>();
        if (files != null) {
            for (int i = 0; i < files.size(); i++) {
                RemoteFile file = files.get(i);
                if (file == null) {
                    continue;
                }
                boolean isFile = !file.isDirectory();
                String destPath = localDir.generateTransferPath(file.getPath(), remoteDir);
                long totalSize = isFile ? file.getSize() : 0L;
                long lastModified = isFile ? file.lastModified() : 0L;
                entries.add(new Entry(isFile, destPath, totalSize, lastModified));
            }
        }
        Collections.sort(entries, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.destPath.compareTo(b.destPath);
            }
        });
        List<Entry> deduped = new ArrayList<>(entries.size());
        String lastPath = null;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (lastPath != null && lastPath.equals(entry.destPath)) {
                continue;
            }
            deduped.add(entry);
            lastPath = entry.destPath;
        }
        return deduped;
    }

    /**
     * 计算 taskKey：{@code hex(SHA-256(prefix + entryCount + 每条(isFile, pathUtf8, totalSize, lastModified)))[0,16])}
     *
     * <p>序列化一律大端，路径显式 UTF-8（禁止默认字符集）。
     */
    public static String compute(List<Entry> entries) {
        int count = entries == null ? 0 : entries.size();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(KEY_PREFIX.getBytes(StandardCharsets.UTF_8));
            ByteBuffer head = ByteBuffer.allocate(4);
            head.putInt(count);
            digest.update(head.array(), 0, 4);
            for (int i = 0; i < count; i++) {
                Entry entry = entries.get(i);
                byte[] pathBytes = entry.destPath.getBytes(StandardCharsets.UTF_8);
                ByteBuffer item = ByteBuffer.allocate(1 + 4 + pathBytes.length + 8 + 8);
                item.put((byte) (entry.isFile ? 1 : 0));
                item.putInt(pathBytes.length);
                item.put(pathBytes);
                item.putLong(entry.totalSize);
                item.putLong(entry.lastModified);
                digest.update(item.array(), 0, item.capacity());
            }
            byte[] hash = digest.digest();
            StringBuilder builder = new StringBuilder(KEY_BYTES * 2);
            for (int i = 0; i < KEY_BYTES; i++) {
                int value = hash[i] & 0xFF;
                builder.append(Character.forDigit(value >>> 4, 16));
                builder.append(Character.forDigit(value & 0x0F, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
