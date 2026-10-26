package top.weixiansen574.hybridfilexfer.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 发送方持有的"跳过集"：接收端回执里 {@code localPath -> 已完成位图}。
 *
 * <p>判定口径（契约 §5.1）：
 * <ul>
 *     <li>bitmap 缺失、或 blockCount 不匹配、或该 index 未完成 → 需要发送；</li>
 *     <li>整文件跳过：bitmap 存在 且 blockCount 匹配 且 全完成。</li>
 * </ul>
 * blockCount 不匹配即"不信任"，天然覆盖"源文件在两次会话之间长度变了"的情况。
 */
public final class ResumeSkipSet {

    /** 空跳过集：全部发送 */
    public static final ResumeSkipSet EMPTY =
            new ResumeSkipSet(Collections.<String, BlockBitmap>emptyMap());

    private final Map<String, BlockBitmap> completedByDestPath;

    public ResumeSkipSet(Map<String, BlockBitmap> completedByDestPath) {
        // 防御性拷贝
        Map<String, BlockBitmap> copy = new HashMap<>();
        if (completedByDestPath != null) {
            for (Map.Entry<String, BlockBitmap> entry : completedByDestPath.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    copy.put(entry.getKey(), entry.getValue());
                }
            }
        }
        this.completedByDestPath = Collections.unmodifiableMap(copy);
    }

    /** 没有该路径的位图时返回 null */
    public BlockBitmap bitmapOf(String destPath) {
        if (destPath == null) {
            return null;
        }
        return completedByDestPath.get(destPath);
    }

    /** 唯一判定入口：true = 该块需要发送 */
    public boolean isSendable(String destPath, int blockIndex, int blockCount) {
        BlockBitmap bitmap = bitmapOf(destPath);
        if (bitmap == null) {
            return true;
        }
        if (bitmap.blockCount() != blockCount) {
            // 不信任：源文件长度变了
            return true;
        }
        return !bitmap.isCompleted(blockIndex);
    }

    /** 整文件跳过判定 */
    public boolean isFileCompleted(String destPath, int blockCount) {
        BlockBitmap bitmap = bitmapOf(destPath);
        return bitmap != null && bitmap.blockCount() == blockCount && bitmap.isAllCompleted();
    }

    public int size() {
        return completedByDestPath.size();
    }

    @Override
    public String toString() {
        return "ResumeSkipSet{entries=" + completedByDestPath.size() + "}";
    }
}
