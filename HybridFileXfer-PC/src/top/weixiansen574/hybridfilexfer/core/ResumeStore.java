package top.weixiansen574.hybridfilexfer.core;

import java.util.List;

/**
 * 续传清单存储（契约 §3.4）。只在接收端（写文件方）持有。
 *
 * <p>实现方（Android: AndroidResumeStore / PC: JdkResumeStore）必须自己吞掉底层异常并降级，
 * <b>绝不能因为清单故障让传输失败</b>。
 */
public interface ResumeStore {

    /** 读取某任务的全部行；无清单时返回空表 */
    List<ResumeEntry> loadTask(String taskKey);

    /** 插入或覆盖一行 */
    void saveEntry(ResumeEntry entry);

    void removeEntry(String taskKey, String localPath);

    void removeTask(String taskKey);

    /** 清理 updatedAt &lt; now - retentionMillis 的行 */
    void purgeExpired(long nowMillis, long retentionMillis);
}
