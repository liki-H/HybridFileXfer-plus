package top.weixiansen574.hybridfilexfer.core;

public class ControllerIdentifiers {
    public static final short SHUTDOWN = 0;
    public static final short LIST_FILES = 1;
    public static final short DELETE_FILE = 2;
    public static final short MKDIR = 3;
    public static final short REQUEST_RECEIVE = 10;
    public static final short REQUEST_SEND = 11;

    // 续传协商（需求B）。值避开已用：0,1,2,3,10,11
    /** 发送方 -> 接收方：本批任务指纹 + 顶层条目 + 校验开关 */
    public static final short RESUME_QUERY = 20;
    /** 发送方 -> 接收方：条目结束哨兵 */
    public static final short RESUME_PLAN_END = 21;
    /** 接收方 -> 发送方：已完成的 block 位图回执 */
    public static final short RESUME_REPLY = 22;
}
