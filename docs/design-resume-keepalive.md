# 设计契约：block 级精准续传（需求B） + root 保活（需求C）

> 状态：**契约（唯一实现依据）**。作者：fullstack-advisor。适用任务：task-3 / task-4 / task-5（下游一律照本文实现，任何偏离必须先改本文）。
> 仓库：`D:\2Code\HybridFileXfer`（Windows / JDK21 可用 / **无 Android SDK**）。
> 编码注意：所有含中文的源码/文档一律 UTF-8 无 BOM；**不要**用 PowerShell 5.1 的 `Get-Content/Set-Content` 读写这些文件（会把中文转成 GBK 乱码）。用编辑工具或 `[IO.File]::WriteAllText($p,$s,(New-Object Text.UTF8Encoding $false))`。

---

## 0. 本文结构（与 task-1 的 9 点要求一一对应）

| 章节 | 对应要求 |
| --- | --- |
| §1 | 现状事实基线（含行号，全部为实测读码结果） |
| §2 | 要求 1：协议（版本、常量、消息布局、时序、暂停策略） |
| §3 | 要求 2：清单 schema 与持久化 |
| §4 | 要求 3：CRC32 续传校验 |
| §5 | 要求 4：ReadFileCall 跳过集 |
| §6 | 要求 5：暂停/恢复门控点 |
| §7 | 要求 7：两端改动清单 + 一致性检查点 |
| §8 | 要求 6：保活架构（**已由 task-2 落地，本节负责追认 + 界定遗留问题**） |
| §9 | 要求 8：无 Android SDK 的验证策略（含已实测通过的命令） |
| §10 | 要求 9：风险与边界 |
| §11 | 假设与待决事项 |

---

## 1. 现状事实基线（实测）

### 1.1 两份 `core/` 是**逐字节相同**的源码副本（重要约束）

| 目录 | 内容 |
| --- | --- |
| `HybridFileXfer-Android/app/src/main/java/top/weixiansen574/hybridfilexfer/core/` | 18 个文件（含 `bean/`、`callback/`），Android 模块直接编译它 |
| `HybridFileXfer-PC/src/top/weixiansen574/hybridfilexfer/core/` | **与上者 18 个文件 MD5 全部相同** |

同理 `top/weixiansen574/nio/DataByteChannel.java` 两份也完全相同（5698 字节）。
**结论：`core/` 的任何改动必须对两棵树写入完全相同的字节**（§9.3 给出可执行的校验命令）。这也是"PC 侧能完整编译测试、Android 侧只能静态审查"仍然安全的前提：Android 编译到的就是 PC 上被测过的同一份代码。

### 1.2 协议现状

| 位置 | 事实 |
| --- | --- |
| `core/HFXService.java:18` | `public static final int VERSION_CODE = 300;`（两份相同） |
| `core/HFXService.java:23-90` | `sendFiles(...)`：起 `ReadFileCall` 线程 + 每通道 `SendFileCall` 线程，随后在控制通道 `readBoolean()` 等接收端结论 |
| `core/HFXService.java:92-148` | `receiveFiles(...)`：起每通道 `ReceiveFileCall` + 单写线程 `WriteFileCall` |
| `core/ControllerIdentifiers.java:4-9` | `SHUTDOWN=0, LIST_FILES=1, DELETE_FILE=2, MKDIR=3, REQUEST_RECEIVE=10, REQUEST_SEND=11` |
| `core/TransferIdentifiers.java` | `END_POINT=-1, FILE=0, FOLDER=1, FILE_SLICE=2, EOF=3, END_OF_INTERRUPTED=4, END_OF_READ_ERROR=5, END_OF_WRITE_ERROR=6` |
| `core/FileBlock.java:7` | `BLOCK_SIZE = 1024*1024` |
| `core/FileBlock.java:27-29` | `getStartPosition() = BLOCK_SIZE * (long) index` |
| `core/FileBlock.java:31-33` | `calcBlockCount() = totalSize / BLOCK_SIZE + 1` —— **对整 MB 文件多算 1 块，是错的，且全仓库无人调用**（已 grep 确认）。**保持原样不动，禁止使用**；以 §2.6 的静态方法为准 |
| `core/SendFileCall.java:48-56` | 块头写入顺序：`short(FILE/FOLDER)` → `int fileIndex` → `UTF path` → `long lastModified` →（仅 FILE）`long totalSize` → `int index` → `int length` → 之后 `data` |
| `core/ReceiveFileCall.java:44-68` | 对应读取；`path` 是**接收端绝对路径**（由发送端 `Directory.generateTransferPath` 生成） |
| `core/WriteFileCall.java:59` | 换文件时 `createAndOpenFile(path,totalSize)`（两端实现均 `setLength(totalSize)`：PC `JdkWriteFileCall.java:38-42`，Android `IOServiceImpl.java:37-48`） |
| `core/WriteFileCall.java:66-69` | **已按 `block.getStartPosition()` seek 补写**（续传的写侧基础已存在） |
| `core/WriteFileCall.java:113-136` | `finishChannel/putBlock/cancel`，单写线程 + 全局按 `(fileIndex,index)` 升序出队 |
| `core/ReadFileCall.java:34-57,71-110` | 扫描与切块耦合在一次遍历里，**从偏移 0 顺序切块，无跳过能力** |
| `droidcore/HFXServer.java:177-192` | `sendFilesToRemote` 写 `REQUEST_RECEIVE` 后 `sendFiles(...)`（**本端是发送方**）；`sendFilesToShelf` 写 `REQUEST_SEND`+文件列表+目录信息后 `receiveFiles(...)`（**本端是接收方**） |
| `core/HFXClient.java:152-178` | `start()` 循环分发 `LIST_FILES/DELETE_FILE/MKDIR/REQUEST_RECEIVE/REQUEST_SEND/SHUTDOWN` |
| `core/HFXClient.java:288-304` | `handleReceiveFiles()` → `receiveFiles(...)`（本端接收方）；`handleSendFiles()` → `sendFiles(...)`（本端发送方） |

### 1.3 角色矩阵（决定"清单归谁"）

| 流程 | 发送方（读文件、持有跳过集） | 接收方（写文件、**持有清单**） |
| --- | --- | --- |
| Android 服务端推给 PC | `HFXServer`（Android 主进程） | `HFXClient`→`JdkHFXClient`（PC） |
| PC 推给 Android | `HFXClient`→`JdkHFXClient`（PC） | `HFXServer`（Android 主进程） |
| Android 客户端收 Android 服务端 | `HFXServer` | `DroidHFXClient`（Android 主进程） |

**清单只存在于接收方**（写文件方），遵循 task 背景中已锁定的结论。

---

## 2. 要求 1：协议

### 2.1 版本号

`core/HFXService.java:18`：

```java
public static final int VERSION_CODE = 301;   // 300 -> 301
```

两份 `core/HFXService.java` 同步改。`HFXServer.java:76` 与 `HFXClient.java:41-46` 都走这个常量，无需另改。
**语义**：301 与 300 不互通（旧的版本校验逻辑直接拒绝连接），符合"新旧不能混用"的要求。

### 2.2 `core/ControllerIdentifiers.java` 新增常量（追加在 `REQUEST_SEND` 之后）

```java
// 续传协商（需求B）。值避开已用：0,1,2,3,10,11
public static final short RESUME_QUERY = 20;      // 发送方 -> 接收方：本批任务指纹 + 顶层条目 + 校验开关
public static final short RESUME_PLAN_END = 21;   // 发送方 -> 接收方：条目结束哨兵
public static final short RESUME_REPLY = 22;      // 接收方 -> 发送方：已完成的 block 位图回执
```

### 2.3 谁先发什么（**唯一顺序，两端都不得变**）

**协商永远由发送方先发起，接收方被动应答**，与"哪端是 Android"无关。因此实现全部落在共享 `core/HFXService.java` 内，Android/PC 两侧不会各写一套。

```
发送方 sendFiles():                          接收方 receiveFiles():
  writeShort(RESUME_QUERY)                     readShort() 必须 == RESUME_QUERY
  writeInt(entryCount)                         readInt() entryCount
  writeUTF(taskKey)                            readUTF() taskKey(对端算的，用于自检)
  writeBoolean(crcEnabled)                     readBoolean() crcEnabled
  writeUTF(senderRoot)                         readUTF() senderRoot
  writeUTF(destRoot)                           readUTF() destRoot
  for entry: ...                               for entry: ...  (共 entryCount 次)
  writeShort(RESUME_PLAN_END)                  readShort() 必须 == RESUME_PLAN_END
                                               // 用收到的 entry 列表重算 taskKey，与对端值比对
                                               // 查清单 -> 过滤 -> 回执
  readShort() 必须 == RESUME_REPLY  <----      writeShort(RESUME_REPLY)
  readInt() replyCount              <----      writeInt(replyCount)
  for reply: 读 path/blockCount/    <----      for reply: 仅在"存在且长度一致且至少 1 位完成"时写
             byteLen/bitmap
  ---- 协商结束，之后才是原有传输逻辑 ----
```

两条流程的时序核对（都已确认不会死锁，因为控制通道两个方向独立）：

* `REQUEST_RECEIVE`（服务端推）：服务端写 `REQUEST_RECEIVE` 后立刻进入 `sendFiles()` 写 `RESUME_QUERY`；客户端在 `HFXClient.start()` 读循环里读到 `REQUEST_RECEIVE` → `handleReceiveFiles()` → `receiveFiles()` 读 `RESUME_QUERY`。TCP 缓冲吸收这段间隔。
* `REQUEST_SEND`（服务端请求、客户端推）：服务端写完 `REQUEST_SEND` + 列表 + 目录后进入 `receiveFiles()` 读 `RESUME_QUERY`；客户端读到 `REQUEST_SEND` → `handleSendFiles()` → `sendFiles()` 写 `RESUME_QUERY`。

### 2.4 消息字段级布局（全部 `java.io.DataInput/DataOutput` 大端，经 `DataByteChannel`）

**RESUME_QUERY（发送方 → 接收方）**

| # | 类型 | 名称 | 说明 |
| --- | --- | --- | --- |
| 1 | short | 消息号 | `20` |
| 2 | int | entryCount | 顶层条目数；`0` 合法（空选择）；**负数非法** |
| 3 | UTF | taskKey | 32 位小写十六进制（§2.5），`entryCount==0` 时写 `""` |
| 4 | boolean | crcEnabled | 本次会话是否每 block 带 CRC32（§4） |
| 5 | UTF | senderRoot | 发送方 `localDir.path`（源根目录，仅用于清单诊断字段） |
| 6 | UTF | destRoot | 接收方目标根 `remoteDir.path` |
| 7 | ×entryCount | 见下 | 条目按 `destPath` 的 `String.compareTo` **升序**排列 |
| 8 | short | 结束哨兵 | `21` |

条目（每条）：

| # | 类型 | 名称 | 说明 |
| --- | --- | --- | --- |
| 1 | boolean | isFile | 目录为 false |
| 2 | UTF | destPath | 接收端绝对路径；**必须与后续 `FileBlock.path` 用同一函数 `Directory.generateTransferPath()` 生成**，因而可直接作为跳过集/位图的键 |
| 3 | long | totalSize | 文件=源文件长度；**目录固定写 0** |
| 4 | long | lastModified | 文件=源 mtime；**目录固定写 0** |

**RESUME_REPLY（接收方 → 发送方）**

| # | 类型 | 名称 | 说明 |
| --- | --- | --- | --- |
| 1 | short | 消息号 | `22` |
| 2 | int | replyCount | ≥0 |
| 3 | ×replyCount | 每条：`UTF localPath`（= 清单里的接收端绝对路径，直接作为跳过集键） → `int blockCount`（§2.6 规范值） → `int byteLen`（必须 `== BlockBitmap.byteLength(blockCount)`）→ `byte[byteLen] bitmap` | 只回"确实有已完成块"的文件 |

**编码实现（不许散落在 `HFXService` 里手写，必须抽成共享类，见 §7.2 `core/ResumeProtocol.java`），签名固定为：**

```java
public final class ResumeProtocol {
    public static void writeQuery(DataByteChannel c, String taskKey, boolean crcEnabled,
                                  String senderRoot, String destRoot,
                                  List<ResumeTaskKey.Entry> entries) throws IOException;
    public static ResumeQuery readQuery(DataByteChannel c) throws IOException;
    /** 接收方写出回执（只用到 entry 的 localPath/blockCount/bitmap 三个字段） */
    public static void writeReply(DataByteChannel c, List<ResumeEntry> entries) throws IOException;
    /** 发送方读出回执：localPath -> 已完成位图，可直接构造 ResumeSkipSet */
    public static Map<String, BlockBitmap> readReply(DataByteChannel c) throws IOException;
}
public final class ResumeQuery {              // 只读数据载体
    public final String taskKey; public final boolean crcEnabled;
    public final String senderRoot; public final String destRoot;
    public final List<ResumeTaskKey.Entry> entries;
}
```

`readQuery`/`readReply` 的**健壮性硬要求**（防流错位/OOM）：

* `readShort()` 不等于期望消息号 → `throw new IOException("unexpected control message: " + msg)`；
* （`readQuery`）`entryCount < 0 || entryCount > 1_000_000` → `throw new IOException("bad entryCount")`；
* （`readQuery`）读完 entryCount 条后 `readShort()` 不等于 `RESUME_PLAN_END` → `throw new IOException("resume plan not terminated")`；
* （`readReply`）`replyCount < 0 || replyCount > 1_000_000` → `throw new IOException("bad replyCount")`；
* （`readReply`）`blockCount < 1 || blockCount > 4_194_304`（= 4TB/1MB）→ `throw new IOException("bad blockCount")`；
* （`readReply`）`byteLen != BlockBitmap.byteLength(blockCount)` → `throw new IOException("bad bitmap length")`；
* 位图用 `DataByteChannel.writeFully(byte[])` / `readFully(byte[])`（见 §7.1 对 `DataByteChannel` 的追加），**禁止**用 `write(byte[])`（`SocketChannel` 上可能部分写）。

**协议层错误一律 `IOException`**：沿用现有异常路径（`SendFileCall/ReceiveFileCall` 的 catch → `onChannelError` → 传输中止 + 弹窗），**不新增错误回调方法**，避免波及 `TransferFileCallback` 的 4 个实现。

### 2.5 `taskKey`：如何稳定识别"同一批任务"

**定义输入**：一次传输的**顶层选择集**（`sendFiles()` 的 `files` 参数，逐个映射为 `destPath`），不含递归展开的子文件。

**规范化**：

1. 逐项生成 `destPath = localDir.generateTransferPath(file.getPath(), remoteDir)`（与块路径同一函数）；
2. `isFile = !file.isDirectory()`；`totalSize = isFile ? file.getSize() : 0`；`lastModified = isFile ? file.lastModified() : 0`；
3. 按 `destPath` 的 `String.compareTo`（UTF-16 code unit 序，两端同为 Java，确定性一致）升序排序；
4. 相同 `destPath` 去重（保留首个）。

**摘要**（`core/ResumeTaskKey.java`）：

```java
public static final String KEY_PREFIX = "HFX-RESUME-V1";
public static final int KEY_BYTES = 16;      // 取 SHA-256 前 16 字节 -> 32 位小写 hex

// 序列化（大端）：
//   KEY_PREFIX 的 UTF-8 字节
//   int entryCount
//   每条： byte isFile(1/0)
//          int  pathUtf8.length
//          byte[pathUtf8]                       // 必须显式 StandardCharsets.UTF_8，禁止默认字符集
//          long totalSize
//          long lastModified
// taskKey = hex( SHA-256(上述字节) 的前 16 字节 )
```

**为什么这样定（写进代码注释）**：

* 只取顶层选择集：两端都能在**不遍历整棵树**的前提下算出同一个键；清单的逐文件状态才是真正的续传依据，树内新增/删除文件不会误命中（新文件没有位图 → 全量重传）。
* **目录的 size/mtime 不参与**：目录 mtime 会因子项变动而变，若参与则"续传几乎永远失效"。
* 顶层文件的 size+mtime 参与：顶层文件被改写 → 键变 → 旧清单自然作废（保守且安全）。
* 双端都算一次并比对（发送方写在 wire 上，接收方用收到条目重算）：**廉价的一致性自检**，实现一旦分叉立刻暴露为"回执 0 条"而不是静默错传。接收方以**自己算的键**做存储查找，不信任对端值。

### 2.6 规范的 block 数（唯一定义，放在 `core/FileBlock.java`）

```java
/** 规范块数：0 字节文件按 1 块处理（与现有 ReadFileCall 的空块语义一致）。禁止用 calcBlockCount()。 */
public static int calcBlockCount(long totalSize) {
    if (totalSize <= 0) return 1;
    return (int) ((totalSize + BLOCK_SIZE - 1) / BLOCK_SIZE);
}
```
旧的非静态 `calcBlockCount()` **原样保留、不许调用**（全仓库无调用点，删除属超范围改动）。

### 2.7 暂停/恢复：**只做本地门控，不占协议**（决策）

* 暂停**不发任何控制消息、不拆 socket、不动清单**；
* 理由：(a) 块是"按 `(fileIndex,index)` 自描述"的，恢复不需要对端配合；(b) 无 wire 状态 → 不可能 desync；(c) 单侧暂停时，对端很快会因为 TCP 窗口/缓冲耗尽自然停住，效果等价；(d) 若要双侧暂停就必然引入新消息和应答状态机，收益为零、风险很大。
* 门控对象见 §6；Android UI 暴露为 `TransferDialog` 的中性按钮；PC 无交互 UI，不暴露（`core` 里能力齐备）。

---

## 3. 要求 2：清单 schema 与持久化

### 3.1 字段（10 项，逐字对应 task 要求）

| 字段 | 类型 | 语义（**唯一解释**） |
| --- | --- | --- |
| `taskKey` | String(32 hex) | §2.5 |
| `direction` | int | **相对于清单持有设备**：`0` = 本机为接收方（写入方）。当前只会出现 0；保留字段为将来"发送方也记清单"留位 |
| `remotePath` | String | 任务级：**发送方源根目录**（= `RESUME_QUERY.senderRoot`）。同一任务所有行相同 |
| `localPath` | String | 文件级：**接收端绝对路径**（= 块里的 `path` = 跳过集键）。主键的一部分 |
| `totalSize` | long | 文件级：源文件总长（来自块头） |
| `lastModified` | long | 文件级：源文件 mtime（来自块头） |
| `blockCount` | int | 文件级：§2.6 规范值。与当前重算值不一致 → 该行作废 |
| `bitmap` | byte[] | 长度 `ceil(blockCount/8)`，**bit i 位于 `bytes[i>>>3]` 的 `1<<(i&7)`（LSB 优先）**；未用填充位必须为 0 |
| `completed` | int | `1` = 该文件全部块已完成；`0` = 未完成 |
| `updatedAt` | long | 毫秒时间戳，用于过期清理与调试 |

### 3.2 Android：复用 `ConfigDB`（SQLite，库名 `config`）

`ConfigDB.java` 改动（**这是唯一允许改 `ConfigDB` 的地方**，CRUD 全部放 `AndroidResumeStore`）：

```java
public static final int VERSION = 2;   // 1 -> 2

private static final String SQL_CREATE_RESUME =
    "CREATE TABLE IF NOT EXISTS resume_manifest (" +
    " task_key      TEXT    NOT NULL," +
    " direction     INTEGER NOT NULL," +
    " remote_path   TEXT    NOT NULL," +
    " local_path    TEXT    NOT NULL," +
    " total_size    INTEGER NOT NULL," +
    " last_modified INTEGER NOT NULL," +
    " block_count   INTEGER NOT NULL," +
    " bitmap        BLOB    NOT NULL," +
    " completed     INTEGER NOT NULL," +
    " updated_at    INTEGER NOT NULL," +
    " PRIMARY KEY (task_key, local_path));";
private static final String SQL_CREATE_RESUME_INDEX =
    "CREATE INDEX IF NOT EXISTS idx_resume_updated ON resume_manifest(updated_at);";

// onCreate(): 现有两张表之后，追加两条 execSQL
// onUpgrade(): if (oldVersion < 2) { execSQL(SQL_CREATE_RESUME); execSQL(SQL_CREATE_RESUME_INDEX); }
```

`onUpgrade` 目前是空实现，**升级路径必须真的实现**（老用户从 v1 库升级不会走 `onCreate`）。

`AndroidResumeStore implements ResumeStore`（新文件，放 `droidcore/`）：

* 构造：`AndroidResumeStore(Context appContext)`，内部 `ConfigDB.getInstance(appContext).getWritableDatabase()`；
* `loadTask`：`SELECT * FROM resume_manifest WHERE task_key=? ORDER BY local_path`；
* `saveEntry`：`INSERT OR REPLACE`（`SQLiteDatabase.CONFLICT_REPLACE`）；
* `removeEntry` / `removeTask` / `purgeExpired(now, retentionMs)`：`DELETE ... WHERE updated_at < ?`；
* **任何 SQLite 异常必须被捕获并降级**：置 `broken=true`，后续调用全部 no-op，打印一次 `printStackTrace`。**绝不能因为清单故障让传输失败**。

### 3.3 PC：用户目录 JSON（`JdkResumeStore`）

* 目录：`System.getProperty("hfx.resume.dir")`，缺省 `System.getProperty("user.home") + "/.hybridfilexfer/resume"`（**必须支持该 system property 覆盖**，否则测试无法隔离）。
* 一个任务一个文件：`<taskKey>.json`。
* 文件格式（**逐字节规范，允许实现手写 IO，但不得引入第三方 JSON 库**）：

```json
{"version":1,"taskKey":"0123456789abcdef0123456789abcdef","direction":0,
 "remotePath":"C:\\pics","entries":[
   {"localPath":"C:\\dst\\a.txt","totalSize":1234,"lastModified":1700000000000,
    "blockCount":2,"completed":0,"updatedAt":1700000000123,"bitmap":"AQ=="}]}
```

* 键顺序固定如上（便于 diff）；`bitmap` 为 `java.util.Base64` 基本编码（PC-only，可用）。
* 字符串转义规则（**唯一允许的子集**，写解析两端都必须一致）：`"` → `\"`，`\` → `\\`，`\u0000`~`\u001F` → `\u00XX`（小写十六进制两位）；其它字符原样 UTF-8 写入。解析器必须能处理 `\" \\ \/ \uXXXX`（其中 `\/` 只读不写）。
* 写入用"临时文件 + 原子替换"：写 `<taskKey>.json.tmp` → `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)`（失败退化为 `REPLACE_EXISTING`）。
* **解析失败/字段缺失 → 删掉该文件并按"无清单"处理**（坏文件不允许拖垮传输）。

### 3.4 `core` 侧接口（三处共享）

```java
// core/ResumeStore.java
public interface ResumeStore {
    List<ResumeEntry> loadTask(String taskKey);
    void saveEntry(ResumeEntry entry);
    void removeEntry(String taskKey, String localPath);
    void removeTask(String taskKey);
    void purgeExpired(long nowMillis, long retentionMillis);
}

// core/ResumeEntry.java —— 全字段 final，构造即校验（bitmap 非 null）
public final class ResumeEntry {
    public final String taskKey; public final int direction;
    public final String remotePath; public final String localPath;
    public final long totalSize; public final long lastModified;
    public final int blockCount; public final BlockBitmap bitmap;
    public final boolean completed; public final long updatedAt;
    public ResumeEntry(String taskKey, int direction, String remotePath, String localPath,
                       long totalSize, long lastModified, int blockCount,
                       BlockBitmap bitmap, boolean completed, long updatedAt) { ... }
}

// core/ResumeSession.java —— 一个传输会话的清单会话（接收方持有）
public final class ResumeSession {
    public static final int FLUSH_BLOCK_INTERVAL = 32;              // 每 32 块落盘一次
    public static final long RETENTION_MS = 7L * 24 * 3600 * 1000;  // 7 天过期
    public ResumeSession(String taskKey, ResumeStore store);        // store 可为 null -> 全程 no-op
    public String taskKey();
    public List<ResumeEntry> loadExisting();                        // 无 store 时返回空表
    public void onBlockWritten(String localPath, long totalSize, long lastModified,
                               int blockIndex, int blockCount);     // 只由单写线程调用
    public void abortFile(String localPath);                        // 删除该文件的行（等价"整文件未完成"）+ 立即落盘
    public void flush();                                            // 脏行落盘（调用方负责先 fsync）
    public void markTaskCompleted();                                // store.removeTask(taskKey)
}
```

`onBlockWritten` 语义（**这是"不过度上报"的唯一保证点**）：

1. 取/建该 `localPath` 的内存行；若已有行但 `blockCount != 传入值` → 视为源文件已变 → 清空位图重建；
2. `bitmap.setCompleted(blockIndex, true)`；`completed = bitmap.isAllCompleted()`；
3. `updatedAt = System.currentTimeMillis()`，标脏；
4. 若 `completed && !wasCompleted` → 立即标脏待落盘（文件收尾时必须 flush，见 §6.4）。

### 3.5 生命周期（两端一致）

| 时机 | 动作 |
| --- | --- |
| 会话开始（`receiveFiles` 协商阶段） | `new ResumeSession(localKey, getResumeStore())`；**首次会话前**做一次 `store.purgeExpired(now, RETENTION_MS)`（低成本，每个会话最多 1 次） |
| 每个块写完 | `onBlockWritten(...)`；写线程按 `FLUSH_BLOCK_INTERVAL` 触发 `force + flush` |
| 文件收尾（关闭 fd 前） | `channel.force(false)` → `closeFile()` → `setLastModified` → `flush()` |
| 会话异常中止 | `finally { resumeSession.flush(); }`（尽力保存已完成块） |
| 会话**成功**完成 | `resumeSession.markTaskCompleted()` → 删除整个任务的清单 |
| 过期 | `purgeExpired`（7 天） |

**不变量（审查时逐条核对）**：

1. 位图只由单写线程 `WriteFileCall` 修改（无并发写）；
2. **只擦不增**：`abortFile` 只删除该文件的行（位图随之消失），不触碰同任务其它文件；"行不存在"与"全未完成"在语义上等价；
3. **落盘前先 `force`**：清单里"已完成"的块，其数据在落盘那一刻已经 `force` 到块设备（默认策略见 §3.6 的取舍）；
4. 记录一条不存在的目标文件是**允许**的（协商阶段用长度校验兜住，见 §5.2）。

### 3.6 关于 fsync 的取舍（明确记录）

"清单不过度上报"要求"先 `force` 再落盘"，代价是每个文件收尾多一次 fsync。传输本身已经"每文件 open + setLength + close"，一次额外 `force(false)` 与之同量级，可接受；周期落盘（32 块 ≈ 32MB）也先 `force`，10GB 传输只多 320 次 fsync。**不允许**为了性能去掉 `force`——那会让断电后的续传产生零填充的伪完成文件。

---

## 4. 要求 3：CRC32 校验（默认关）

### 4.1 开关来源

| 端 | 来源 |
| --- | --- |
| Android | `Config.isBlockCrcEnabled()`（SharedPreferences 键 `"block_crc"`，默认 `false`），由 `MainActivity` 菜单项切换；开服/连服时 `HFXService.setBlockCrcEnabled(config.isBlockCrcEnabled())` |
| PC | 命令行 `--crc`（等价 `-crc`）；其余一律 false。`Main.java` 解析后 `client.setBlockCrcEnabled(true)` |

`core/HFXService.java` 新增：

```java
protected volatile boolean blockCrcEnabled = false;
public void setBlockCrcEnabled(boolean enabled) { this.blockCrcEnabled = enabled; }
```

**发送方决定、接收方无条件遵从**（接收方自己的设置项在"收"时无效，仅在"发"时生效）。

### 4.2 协议位置（宽度 int）

在 `TransferIdentifiers.FILE` 块头里，**紧跟在 `int length` 之后、`data` 之前**：

```
short FILE | int fileIndex | UTF path | long lastModified | long totalSize
           | int index | int length | [int crc32]  | data[length]
```

* `crc32` 是 **`int`（4 字节）**，对该块 `length` 个字节（从块起始位置起的实际字节数，可能 < 1MB）计算，多项式/反射/初值/终值与 `java.util.zip.CRC32` 完全一致（zlib 标准，"123456789" → `0xCBF43926`）。
* `crcEnabled == false` 时**完全不写、不读、不算**：wire 上 0 字节开销、CPU 0 开销（严格满足"未开启时零开销"）。**若任何实现选择在校验关闭时仍然发送该字段，则必须发送 `0`**（接收端在 `crcEnabled==false` 时不得校验）。
* **明确决策：不在 `FileBlock` 上新增 `crc32` 字段。** 理由：`FileBlock` 的 7 参构造在 `ReadFileCall:74/88/103`、`WriteFileCall`、`SendFileCall` 以及 4 个哨兵常量里都被使用，加字段会连锁改构造签名（两份 `core/` × 全部子类），而 CRC 只在 `SendFileCall`/`ReceiveFileCall` 各 1 处使用、且只在写/读那一刻算一次，放在 `FileBlock` 里没有收益。
   **若将来确需该字段**：追加到构造参数**末尾** `int crc32`，并在 `crcEnabled == false` 时一律传 `0`；此改动属超范围，需先改本文。
* **顺序依赖**：`int crc32` 必须在 `int length` 之后、`data` 之前。发送端先算 CRC 再写头（`buffer` 此时的 `position == length`，用绝对索引算），接收端先读长度→读 crc→读满 data→再校验，校验放在 `putBlock` 之前。
* `FOLDER` 块头不变。

### 4.3 实现（`core/Crc32.java`，禁止用 `java.util.zip.CRC32.update(ByteBuffer)`）

`CRC32.update(ByteBuffer)` 是 Java 9 API，Android API 24 上没有；且 native direct buffer 不能 `array()`。因此**自实现**：

```java
public final class Crc32 {
    private static final int[] TABLE = new int[256];   // static 初始化，多项式 0xEDB88320
    /** 绝对索引计算，不改变 buf.position()/limit()。 */
    public static int compute(ByteBuffer buf, int offset, int length);
}
```

* 发送侧（`SendFileCall`）：`buffer` 当前 `position == length`（`ReadFileCall` 读满后未 flip），用绝对索引算，之后照常 `flip()` 写数据；
* 接收侧（`ReceiveFileCall`）：`buffer.position == length`，同样绝对索引算。

### 4.4 校验失败处理（写端）

```java
int expected = channel.readInt();                        // crcEnabled 时
int actual   = Crc32.compute(buffer, 0, length);
if (actual != expected) {
    writeFileCall.abortResumeFile(path);                 // 整文件标记未完成（清空该文件所有位 + 落盘）
    throw new IOException("CRC32 mismatch: " + path + " block=" + index
            + " expected=0x" + Integer.toHexString(expected)
            + " actual=0x"   + Integer.toHexString(actual));
}
```

* 该块**不写盘**（在 `putBlock` 之前校验）；
* 抛出的 `IOException` 走 `ReceiveFileCall` 现有 catch：`finishChannel(tIndex)` → `callback.onChannelError(iName, ERROR_TYPE_EXCEPTION, e.toString())` → 上层 `onIncomplete()`/`onWriteFileError` 路径；整批传输中止；
* 该文件位图被清空 → **下一轮会话整文件重传**（满足"下轮重传"）；
* 只清"该文件"的位，不动其它文件，不清空整个任务。

---

## 5. 要求 4：ReadFileCall 跳过集

### 5.1 数据结构

```java
// core/BlockBitmap.java
public final class BlockBitmap {
    public static int byteLength(int blockCount) { return (blockCount + 7) / 8; }  // blockCount<=0 时为 0
    public BlockBitmap(int blockCount);            // 分配得零填充（= 全未完成）
    public int blockCount();
    public int byteLength();
    public boolean isCompleted(int blockIndex);    // 越界 -> false
    public void setCompleted(int blockIndex, boolean completed);   // 越界 -> 忽略
    public boolean hasCompleted();                 // 至少 1 位
    public boolean isAllCompleted();
    public byte[] toBytes();                       // 长度 byteLength()，直接映射语义
    public static BlockBitmap fromBytes(int blockCount, byte[] bytes);  // 长度不符 -> 抛 IllegalArgumentException
}

// core/ResumeSkipSet.java
public final class ResumeSkipSet {
    public static final ResumeSkipSet EMPTY = new ResumeSkipSet(Collections.<String, BlockBitmap>emptyMap());
    public ResumeSkipSet(Map<String, BlockBitmap> completedByDestPath);  // 内部防御性拷贝
    public BlockBitmap bitmapOf(String destPath);                        // 无 -> null
    /** 唯一判定入口：bitmap 缺失、或 blockCount 不匹配、或该 index 未完成 -> true（要发） */
    public boolean isSendable(String destPath, int blockIndex, int blockCount);
    /** 整文件跳过判定：bitmap 存在 且 blockCount 匹配 且 全完成 */
    public boolean isFileCompleted(String destPath, int blockCount);
}
```

`blockCount` 不匹配即"不信任"，天然覆盖了"源文件在两次会话之间长度变了"的情况。

### 5.2 接收端如何生成回执（`HFXService.receiveFiles` 内，协商阶段）

对清单里 `taskKey` 命中的每一行：

```java
long len = writeFileCall.queryFileLength(e.localPath);      // 新抽象：不存在返回 -1
if (len != e.totalSize) {                                   // 目标被删/被改/长度不符
    session.abortFile(e.localPath);                         // 清位并删除该行
    continue;                                               // 不回执 -> 发送端整文件重传
}
if (!e.bitmap.hasCompleted()) continue;                      // 没有已完成块，不必回执（省流量）
if (e.bitmap.isAllCompleted()) writeFileCall.touchFile(e.localPath, e.lastModified);  // 修 mtime
reply.add(e);
```

长度校验是**必需**的：否则"用户手工删掉了半成品文件"会被误判为可续传，最终产出一个前半截全零的坏文件。

### 5.3 `ReadFileCall` 改造（伪代码，替换现有 `readToDeque`）

```java
// 新增字段与注入（保持现有构造函数签名不变，避免 4 个子类构造签名连锁改动）
private ResumeSkipSet skipSet = ResumeSkipSet.EMPTY;
private boolean crcEnabled = false;
private TransferGate gate = TransferGate.NOOP;
public void setSkipSet(ResumeSkipSet s)   { this.skipSet = (s == null) ? ResumeSkipSet.EMPTY : s; }
public void setCrcEnabled(boolean b)      { this.crcEnabled = b; }
public void setGate(TransferGate g)       { this.gate = (g == null) ? TransferGate.NOOP : g; }
public TransferGate getGate()             { return gate; }
public boolean isCrcEnabled()             { return crcEnabled; }

private void readToDeque(RemoteFile file) throws Exception {
    fileIndex++;
    String destPath = localDir.generateTransferPath(file.getPath(), remoteDir);
    if (file.isDirectory()) {                       // 目录块永远重发（幂等）
        deque.add(new FileBlock(false, fileIndex, destPath, file.lastModified(), 0, 0, null));
        return;
    }
    FileChannel channel = openFile(file.getPath());
    long length = channel.size();
    long lastModified = file.lastModified();
    int blockCount = FileBlock.calcBlockCount(length);
    if (skipSet.isFileCompleted(destPath, blockCount)) {   // 整文件跳过：不开缓冲、不读盘
        closeFile();
        return;
    }
    for (int i = 0; i < blockCount; i++) {
        if (!gate.awaitUnpaused()) throw new InterruptedException("transfer aborted");   // §6
        int blkSize = (int) Math.min(length - (long) i * FileBlock.BLOCK_SIZE, FileBlock.BLOCK_SIZE);
        if (blkSize < 0) blkSize = 0;
        if (!skipSet.isSendable(destPath, i, blockCount)) continue;   // 已完成块：不占缓冲、不读盘
        ByteBuffer buffer = buffers.take();
        buffer.clear();
        buffer.limit(blkSize);
        if (blkSize > 0) {
            channel.position((long) i * FileBlock.BLOCK_SIZE);        // 随机定位（替代原来的顺序读）
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) == -1) throw new java.io.EOFException("unexpected EOF: " + file.getPath());
            }
        }
        deque.add(new FileBlock(true, fileIndex, destPath, lastModified, length, i, buffer));
    }
    closeFile();
}
```

要点：

* 原来的 `while (remaining > 0)` 用 `remaining -= blkSize` 推进；改成**按下标循环 + 每块 `channel.position()` 定位**，这样才能跳块。两端 `openFile()` 返回的 `FileChannel` 都支持 `position()`（PC `RandomAccessFile` 通道；Android `FileInputStream` 通道）。
* 顺手修掉原实现的一个真实缺陷：`while (buffer.hasRemaining()) channel.read(buffer);` 在读到 `-1` 时会**死循环**（position 不前进）。新循环加了 `-1` 检查。这是被改写循环内的必要修正，不算超范围。
* **0 字节文件**：`blockCount == 1`、`i == 0`、`blkSize == 0`、不读盘、照旧发一个空块 → 与原语义一致；若该块已完成则整文件跳过。
* 完全跳过的文件**不占任何缓冲区块**（这一点很重要：Android 侧缓冲池是 JNI 直接内存，全局共享，泄漏会卡死其它通道）。

---

## 6. 要求 5：暂停/恢复门控点

### 6.1 `core/TransferGate.java`（唯一门控）

```java
public class TransferGate {
    /** 永不暂停的门控（未注入时的默认值），单例。 */
    public static final TransferGate NOOP = new TransferGate() {
        @Override public void pause() {}
        @Override public void resume() {}
        @Override public void abort() {}
        @Override public boolean awaitUnpaused() { return true; }
        @Override public boolean isPaused() { return false; }
        @Override public boolean isAborted() { return false; }
    };

    private final Object lock = new Object();
    private boolean paused;
    private boolean aborted;

    public void pause()  { synchronized (lock) { if (!aborted) paused = true; } }
    public void resume() { synchronized (lock) { paused = false; lock.notifyAll(); } }
    /** 会话结束/异常/取消：所有等待者立即返回 false。幂等、不可逆。 */
    public void abort()  { synchronized (lock) { aborted = true; paused = false; lock.notifyAll(); } }
    public boolean isPaused()  { synchronized (lock) { return paused; } }
    public boolean isAborted() { synchronized (lock) { return aborted; } }
    /** true=继续；false=会话已中止，调用方走自己的结束分支。允许被 interrupt 打断（沿用现有异常语义）。 */
    public boolean awaitUnpaused() throws InterruptedException {
        synchronized (lock) { while (paused && !aborted) lock.wait(); return !aborted; }
    }
}
```

`abort()` 的存在解决一个真实死锁：暂停期间若对端断线/写端报错，等待者必须能被唤醒。**没有 `abort()` 的门控设计不通过评审。**

### 6.2 门控只加在 4 个循环的**顶部**（每轮一次判定）

| 文件 | 插入点（行号=改前） | 中止分支处理 |
| --- | --- | --- |
| `core/ReadFileCall.java:95`（块循环顶部，见 §5.3）与 `:37`（文件循环顶部）/ `:62`（递归遍历循环顶部） | `if (!gate.awaitUnpaused()) throw new InterruptedException("transfer aborted");` | 走现有 `catch (Exception e)` → 塞 `READ_ERROR` 哨兵 → 发送端写 `END_OF_READ_ERROR` → 接收端 `cancel()` |
| `core/SendFileCall.java:27-28`（`while(true)` 顶部、`takeBlock()` 之前） | `if (!gate.awaitUnpaused()) { channel.writeShort(TransferIdentifiers.END_OF_INTERRUPTED); callback.onChannelError(connection.iName, TransferFileCallback.ERROR_TYPE_INTERRUPT, null); break; }` | 与既有 `INTERRUPT` 分支完全一致 |
| `core/ReceiveFileCall.java:34-35`（`while(true)` 顶部、`readShort()` 之前） | `if (!gate.awaitUnpaused()) { writeFileCall.finishChannel(tIndex); return null; }` | 与既有 `EOF` 分支的收尾动作一致 |
| `core/WriteFileCall.java:37`（`while (block != null)` 顶部、`takeBlock()` 之前） | `if (!gate.awaitUnpaused()) { cancel(); break; }` | `cancel()` 回收尚未写盘的缓冲区块；`break` 后仍会执行尾部 `closeFile()+setLastModified()`，不泄漏 fd |

**为什么这些点安全（逐条，评审依据）**：

1. **永不打断块内状态**：`SendFileCall` 只在"取块前"判；`ReceiveFileCall` 只在"读块头前"判；一旦开始写/读一个块，必须写完 —— 否则 wire 上会残留半个块头/半个 data，socket 没拆也救不回来。
2. `ReadFileCall` 判在"取缓冲前"：暂停时不占新缓冲；`i`/`remaining` 都是局部变量，天然的续点。
3. `WriteFileCall` 判在 `takeBlock()` 前：块队列与 `channelFinished` 状态不受影响；已入队未写盘的块由 `cancel()` 回收。
4. **背压天然有界**：即使只暂停发送侧，读线程也会在 `buffers.take()` 上自然阻塞（池空），不会无限占内存。
5. **暂停不改变清单语义**：清单只在"块写成功后"推进；暂停时没有块写 → 清单停在同一状态。

### 6.3 门控实例的作用域 + **必须存在的强制唤醒路径**

* `HFXService` 新增 `private volatile TransferGate currentGate;` + `public TransferGate getCurrentGate()`（返回 null 表示当前无传输）；
* `sendFiles()` / `receiveFiles()` **各自在方法开头 new 一个 gate**，`finally { gate.abort(); currentGate = null; }`；
* gate 传给 `readFileCall`/`writeFileCall`（`setGate`），`SendFileCall`/`ReceiveFileCall` 通过 `readFileCall.getGate()` / `writeFileCall.getGate()` 取用（**不改进这两个类的构造函数**）；
* **强制唤醒 API（必须实现，否则会挂死）**：

```java
// core/HFXService.java
public final void abortTransfer() { TransferGate g = currentGate; if (g != null) g.abort(); }
```

必须调用 `abortTransfer()` 的既有路径（这三处都在"关闭 socket / 销毁 server"之前）：

| 位置 | 原因 |
| --- | --- |
| `core/HFXClient.java:215-233` `handleShutdown()` 开头 | 收到 `SHUTDOWN` 后关 socket；若此时处于暂停态，传输线程都阻塞在 `awaitUnpaused()`，不 abort 就永远不退出（线程 + 缓冲泄漏，且 Android 侧前台服务让进程一直活着） |
| `droidcore/HFXServer.java:263-291` `disconnect()` 开头 | 用户在暂停中点了"停止服务/退出"，同上 |
| `HFXServer.closeServerSocket()`（`:155-163`）之前 | 端口绑定失败/启动阶段收尾，宁多勿漏 |

> 判断准则：**任何会让 socket 不在预期内关闭的代码路径，都必须先 `abortTransfer()`**。`finally` 里的 `gate.abort()` 只覆盖"方法正常/异常返回"这一种情况，不覆盖"别的线程正在关闭连接"。

### 6.4 `WriteFileCall` 的清单/CRC 相关新增（签名级）

```java
private ResumeSession resumeSession;     // 可为 null
private boolean crcEnabled;
private TransferGate gate = TransferGate.NOOP;
private int writtenSinceFlush;
public void setGate(TransferGate g);     // null -> NOOP
public TransferGate getGate();
public void setResumeSession(ResumeSession s);
public void setCrcEnabled(boolean b);
public ResumeSession getResumeSession();

public final void abortResumeFile(String path);        // 供 ReceiveFileCall 的 CRC 失败调用
public final void touchFile(String path, long lm);      // 供协商阶段修 mtime（包了 protected setFileLastModified）
public final long queryFileLength(String path);         // 包装新抽象 lengthOfFile()，异常返回 -1

/** 新增抽象（4 个子类各实现 1 个方法） */
protected abstract long lengthOfFile(String path) throws Exception;
```

写循环内（`core/WriteFileCall.java:75-85` 之间）追加：

```java
// channel.write(data) 成功之后
if (resumeSession != null) {
    int blockCount = FileBlock.calcBlockCount(block.totalSize);
    if (block.index < 0 || block.index >= blockCount) {
        throw new IOException("bad block index " + block.index + " for " + block.path
                + " (blockCount=" + blockCount + ")");
    }
    resumeSession.onBlockWritten(block.path, block.totalSize,
                                 block.lastModified, block.index, blockCount);
    if (++writtenSinceFlush >= ResumeSession.FLUSH_BLOCK_INTERVAL) {
        forceCurrentChannel();
        resumeSession.flush();
        writtenSinceFlush = 0;
    }
}
```

文件切换处（`core/WriteFileCall.java:51-60`，关闭上一个文件）改为：

```java
if (lastChannel != null) {
    forceCurrentChannel();                       // 先落数据
    closeFile();
    setLastModified(lastBlock.path, lastBlock.lastModified);
    if (resumeSession != null) resumeSession.flush();   // 再落清单
    writtenSinceFlush = 0;
}
```

尾部（`:87-90`）同样 先 `force` → `closeFile()` → `setLastModified` → `flush()`。
`forceCurrentChannel()`：`if (lastChannel != null && lastChannel.isOpen()) { try { lastChannel.force(false); } catch (Throwable ignored) {} }`。

### 6.5 暂停时"socket / 缓冲 / 清单"三者为什么仍然一致

| 维度 | 暂停时的状态 | 恢复后 |
| --- | --- | --- |
| socket | 未关闭、无半块：所有门控点都在"块边界" | 直接从下一个块头开始，流对齐 |
| 缓冲 | 已 checkout 的 ≤ 池容量（Android 默认 256、PC 由协议决定），暂停期间不再新增 checkout | `take()` 立即返回，无需重建 |
| 清单 | 不含正在传输的块（只有已写成功的块） | 继续按块推进；暂停/恢复对清单不可见 |
| 门控 | `paused=true`；`abort()` 可强制解除 | `resume()`；或会话结束时 `abort()` |

**已知局限（写进注释）**：暂停期间对端断线不会被本端立即发现（线程阻塞在 `awaitUnpaused()`），要等用户"继续"或取消。这是"仅本地门控"换来的简单性代价，属可接受行为。

---

## 7. 要求 7：两端改动清单 + 一致性检查点

### 7.1 `core/`（**两份逐字节相同，必须同改**）

| 文件 | 动作 | 内容 |
| --- | --- | --- |
| `core/HFXService.java` | 改 | `VERSION_CODE 301`；`blockCrcEnabled`+`setBlockCrcEnabled`；`currentGate`+`getCurrentGate`；`sendFiles/receiveFiles` 开头插入协商逻辑（用 `ResumeProtocol`），`finally` 收尾；新增 `protected abstract ResumeStore getResumeStore();` |
| `core/ControllerIdentifiers.java` | 改 | `RESUME_QUERY/RESUME_PLAN_END/RESUME_REPLY = 20/21/22` |
| `core/FileBlock.java` | 改 | 新增 `public static int calcBlockCount(long)`（§2.6）。旧实例方法原样保留 |
| `core/ReadFileCall.java` | 改 | skipSet/crcEnabled/gate 注入；`readToDeque` 重写（§5.3）；三处 `awaitUnpaused` |
| `core/WriteFileCall.java` | 改 | gate/resumeSession/crcEnabled + `abortResumeFile/touchFile/queryFileLength/lengthOfFile` + 周期落盘与 force（§6.4） |
| `core/SendFileCall.java` | 改 | 门控；`crcEnabled` 时多写 `int crc32` |
| `core/ReceiveFileCall.java` | 改 | 门控；`crcEnabled` 时多读 `int crc32` 并校验（§4.4）；`index` 越界校验 |
| `core/HFXClient.java` | 改 | 仅在 `handleShutdown()`（`:215-233`）开头加一行 `abortTransfer();`。协商与门控全部在 `HFXService` 内，`handleSendFiles/handleReceiveFiles` 无需改 |
| `core/BlockBitmap.java` | **新增** | §5.1 |
| `core/ResumeSkipSet.java` | **新增** | §5.1 |
| `core/ResumeEntry.java` | **新增** | §3.4 |
| `core/ResumeStore.java` | **新增** | §3.4 |
| `core/ResumeSession.java` | **新增** | §3.4 |
| `core/ResumeTaskKey.java` | **新增** | §2.5（含 `Entry` 静态内部类与 `fromSelection(files, localDir, remoteDir)`） |
| `core/ResumeProtocol.java` | **新增** | §2.4（+ `ResumeQuery` 载体类） |
| `core/TransferGate.java` | **新增** | §6.1 |
| `core/Crc32.java` | **新增** | §4.3 |

`nio/DataByteChannel.java`（两份）追加两个方法（避免部分写）：

```java
public void writeFully(ByteBuffer src) throws IOException { while (src.hasRemaining()) { if (origin.write(src) == -1) throw new EOFException(); } }
public void writeFully(byte[] b) throws IOException { writeFully(ByteBuffer.wrap(b)); }
```

### 7.2 Android 侧（`HybridFileXfer-Android/app/src/main`）

| 文件 | 动作 | 内容 |
| --- | --- | --- |
| `aidl/.../IIOService.aidl` | 改 | 追加 `long getFileLength(String path) = 14;`（14/15/16 未被占用，只用到 14） |
| `IOServiceImpl.java` | 改 | 实现 `getFileLength`：`File f = new File(path); return f.isFile() ? f.length() : -1;` |
| `IOService.java` | 改 | `getUserServiceArgs(...).version(1)` → **`.version(2)`**（AIDL 变更后必须让旧 daemon 进程重建） |
| `droidcore/DroidWriteFileCall.java` | 改 | 实现 `lengthOfFile(path)` → `ioService.getFileLength(path)`（注意 `IIOService.Stub` 的 `RemoteException` 需按现有惯例 throws Exception） |
| `droidcore/DroidReadFileCall.java` | 不改 | `openFile` 已是 `FileInputStream` 通道，支持 `position()` |
| `droidcore/HFXServer.java` | 改 | 实现 `getResumeStore()` → `new AndroidResumeStore(ctx)`（需要 Context，改造见 §7.4）；`attachContext(Context)`；`disconnect()`（`:263`）与 `closeServerSocket()`（`:155`）前调用 `abortTransfer()` |
| `droidcore/DroidHFXClient.java` | 改 | 实现 `getResumeStore()`（该类已有 `context` 字段） |
| `droidcore/AndroidResumeStore.java` | **新增** | §3.2 |
| `ConfigDB.java` | 改 | `VERSION 2` + 建表/建索引 + `onUpgrade`（§3.2） |
| `Config.java` | 改 | `isBlockCrcEnabled()/setBlockCrcEnabled(boolean)`（键 `"block_crc"`，默认 false） |
| `MainActivity.java` | 改 | 菜单项切换 CRC；起服时 `server.setBlockCrcEnabled(config.isBlockCrcEnabled())`；`onServiceConnected` 里 `server.attachContext(getApplicationContext())` |
| `ClientActivity.java` | 改 | 建好 `DroidHFXClient` 之后、`new ConnectServerTask(...).execute()` 之前调 `client.setBlockCrcEnabled(Config.getInstance(context).isBlockCrcEnabled())`；`onServiceConnected` 保持现状（已用 ApplicationContext 绑定） |
| `TransferDialog.java` | 改 | `show()` 增加 `setNeutralButton`（暂停/继续），构造参数追加 `Supplier<TransferGate>`；`TransferActivity` 传 `server::getCurrentGate` |
| `res/menu/main_menu.xml` | 改 | 追加 checkable 项 `@+id/block_crc` |
| `res/values/strings.xml` / `res/values-zh/strings.xml` | 改 | 追加 `pause` / `resume` / `block_crc`（英文默认 + 中文，其它语言回落英文） |
| `app/build.gradle` | 改 | `versionCode 300` → `301`（便于与协议版本对齐） |

**保活相关的既有改动（task-2 已落地，本轮不动）**：`foreground/TransferForegroundService.java`、`foreground/RootWatchdog.java`、`AndroidManifest.xml` 的 4 个权限与 service 声明、MainActivity/ClientActivity 的 ApplicationContext 绑定、`keepalive_*` 文案。见 §8。

### 7.3 PC 侧（`HybridFileXfer-PC/src`）

| 文件 | 动作 | 内容 |
| --- | --- | --- |
| `core/*` | 改 | 与 §7.1 完全一致（逐字节相同） |
| `jdkcore/JdkHFXClient.java` | 改 | 实现 `getResumeStore()` → `new JdkResumeStore()` |
| `jdkcore/JdkWriteFileCall.java` | 改 | 实现 `lengthOfFile(path)`：`File f = new File(path); return f.exists() && f.isFile() ? f.length() : -1;` |
| `jdkcore/JdkReadFileCall.java` | 不改 | `RandomAccessFile` 通道支持 `position()` |
| `jdkcore/JdkResumeStore.java` | **新增** | §3.3 |
| `Main.java` | 改 | `--crc` 参数；`-v` 时打印协议版本（`HFXService.VERSION_CODE`）便于两端排查 |
| `test/**` | **新增** | §9.2（自测用例、shim、运行脚本；**不进生产源码目录**） |

### 7.4 `HFXServer` 需要 Context 的最小改法（避免大改）

`HFXServer` 现有构造 `HFXServer(IIOService ioService)`，`MainActivity:219-262` 里 `new HFXServer(iioService)`。`AndroidResumeStore` 只需要 App Context 去拿 `ConfigDB`。两种做法二选一，**契约选定后者**：

1. ~~改构造签名~~（要连带改 `MainActivity`/`StartServerTask`/`TransferActivity` 等）
2. **新增 `public void attachContext(Context c)`**：`MainActivity.onServiceConnected` 里 `server = new HFXServer(iioService); server.attachContext(getApplicationContext());`，`getResumeStore()` 内部若 ctx 为空则返回 `null`（降级为"无续传"，绝不影响传输）。改动最小、失败安全。

> `AndroidResumeStore` 内部一律用 `getApplicationContext()` 拿 DB，不得持有 Activity。

### 7.5 两端一致性检查点（评审必须逐条打勾）

1. **`core/` 逐文件 MD5 相同**（18 老 + 9 新 = 27 个文件；§9.3 命令）；
2. `VERSION_CODE == 301` 两处相同；
3. `ControllerIdentifiers` 三个新常量值相同（20/21/22）；
4. 消息读写只存在于 `core/ResumeProtocol.java`——**`grep -n "RESUME_QUERY" */core/*.java` 只允许命中 `ControllerIdentifiers.java`、`ResumeProtocol.java`、`HFXService.java`**；
5. `core/` 里**禁止**出现 Android/PC 专属 API：不得 `import android.`、不得 `java.util.Base64`、不得 `java.util.zip.CRC32.update(ByteBuffer)`、不得 `List.of`/`String.repeat`/`var`（Android `sourceCompatibility 1.8`）；
6. 位图字节序两端一致：只有 `BlockBitmap` 定义 `1<<(i&7)`；
7. 默认值一致：`crcEnabled` 默认 false；`ResumeSkipSet.EMPTY` 为"全发"；
8. `DataByteChannel` 两份 MD5 相同；
9. 清单字段名映射：Android 列名（snake_case）↔ PC JSON 键（camelCase）↔ `ResumeEntry` 字段名，一一对应（§3.1 表为准）。

---

## 8. 要求 6：保活架构（**追认 task-2 已落地的实现 + 界定本轮边界**）

### 8.1 关键结论：先看清"谁会被回收"

| 对象 | 所在进程 | 谁会杀它 | 现有保护 |
| --- | --- | --- | --- |
| `HFXServer` + `ServerSocketChannel` + 传输线程 + `WriteFileCall` | **主进程**（`top.weixiansen574.hybridfilexfer`） | LMK / 后台清理 / Doze | task-2：`TransferForegroundService`（dataSync）+ `PARTIAL_WAKE_LOCK` |
| `IOServiceImpl`（真正干 IO 的） | **`:IOService`**（普通模式 `bindService`；root 模式 Shizuku daemon 绑定） | 解绑即 `destroy()`→`System.exit(0)`；也会被 LMK 杀 | task-2：`RootWatchdog`（root 下 `su` 常驻脚本轮询 `pidof`，缺失则 `am start-service`） |

**被保活对象是主进程；`:IOService` 由 Shizuku daemon / watchdog 保活。** 二者不可混为一谈（把 `:IOService` 改成前台服务是错的：它不是被回收的主要对象，而且会额外占一个前台服务名额）。

### 8.2 已落地实现（下游不得重造，只允许增量）

| 项 | 实际值（代码为准） |
| --- | --- |
| 前台服务类 | `top.weixiansen574.hybridfilexfer.foreground.TransferForegroundService`（**主进程**，Manifest 未指定 `android:process`） |
| Manifest 声明 | `android:foregroundServiceType="dataSync"`、`exported="false"`、`stopWithTask="false"`、`tools:targetApi="34"` |
| 权限 | `WAKE_LOCK` / `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` / `POST_NOTIFICATIONS`（已加） |
| 通知渠道 | `CHANNEL_ID = "transfer_channel"`，`IMPORTANCE_LOW`，`setShowBadge(false)`；`NOTIFICATION_ID = 1`；小图标 `R.drawable.baseline_speed_24` |
| `startForeground` 时机 | `onCreate()` 内（满足 `startForegroundService` 的 5s 窗口）；API 29+ 带 `FOREGROUND_SERVICE_TYPE_DATA_SYNC` |
| WakeLock | `PowerManager.PARTIAL_WAKE_LOCK`，tag `HybridFileXfer:TransferForegroundService`，`setReferenceCounted(false)`；**`onCreate()` acquire / `onDestroy()` release**，异常一律吞掉降级 |
| 静态入口 | `TransferForegroundService.start(Context)` / `start(Context, boolean rootMode)` / `stop(Context)`，内部统一 `getApplicationContext()` |
| 解耦方式 | `MainActivity`/`ClientActivity` 一律用 `appContext.bindService(...)`、`Shizuku.bindUserService(IOService.getUserServiceArgs(appContext), this)`，并用 `bound` 标记避免重复解绑；`unbindService()` 内先 `TransferForegroundService.stop()` 再解绑 |
| root watchdog | `foreground/RootWatchdog.java`：`su -c id` 检测 root → 写 `filesDir/hfx_root_watchdog.sh`（UTF-8 无 BOM）→ `su -c "sh <脚本>"` 常驻；脚本每 30s `pidof "<pkg>:IOService"`，缺失则 `am start-service -n <pkg>/.IOService`；pid 文件 + `/proc/<pid>/cmdline` 校验防误杀；无 root/被拒 → 静默降级 |
| 通知权限 | `MainActivity` API 33+ 请求 `POST_NOTIFICATIONS`，拒绝不影响传输 |

### 8.3 本轮（需求B）对保活的**硬约束**

1. 传输期间**不得**停止前台服务/释放 WakeLock（否则息屏传输会被打断）；
2. `TransferForegroundService` 生命周期内的传输状态（清单、gate）**不得**挂在 Activity 上；
3. 保活的任何代码路径**不得**吞掉传输异常（`TransferForegroundService` 内部 catch 只用于自身降级）；
4. 本轮**不修改** `foreground/` 下任何文件（避免与需求C 的回归验证冲突）。

### 8.4 遗留 gap（本轮不修，单列 P1，必须在交付说明里写明）

> **P1-1：服务器生命周期仍与 `MainActivity` 实例绑定。**
> `MainActivity.onDestroy()`（现 `:563-585`）会 `HFXServer.instance.disconnect(...)` 并 `unbindService()`（后者内部 `TransferForegroundService.stop()` + `IOServiceImpl.destroy()` → `System.exit(0)`）。因此"Activity 被销毁 → 传输中断 + 保活服务停止"仍是既有语义，需求C 第 3 点只达成 **Context 级解耦**。
>
> **P1-1 方案草案（下一轮）**：新增 `AppCore` 单例（Application 作用域）持有 `IIOService`、`HFXServer`、`ServiceConnection`、`bound` 标记与监听者列表；两个 Activity 只做观察者注册/注销；`onDestroy` 不再 disconnect；前台服务启停跟随"服务器/传输"而不是 Activity。**必须与 `TransferGate`/`ResumeSession` 的会话作用域一起设计**，否则会出现"新 Activity 拿到旧 server 状态"。本轮 `TransferGate`/`ResumeSession` 已经是"每次 `sendFiles/receiveFiles` 新建"的会话级对象，不会给 P1 挖坑。
>
> **P1-1 附带风险**：当前 `unbindService()` 会 `TransferForegroundService.stop()`；若将来 Activity 在传输中被销毁，WakeLock 会被释放。属 P1 一并修。

---

## 9. 要求 8：无 Android SDK 的验证策略

> 实测环境事实：`javac` 为 **JDK 21**（`C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`）；**全盘无 `android.jar`**，无法 gradle 构建。已实测：**`core/` + `nio/` 用 javac 可以脱离 Android 编译通过**（见 §9.1，本轮已跑通）。

### 9.1 静态与编译自检（Android 侧能做的全部）

| 步骤 | 命令（PowerShell，实测可用） | 通过标准 |
| --- | --- | --- |
| S1 编译 Android `core/`+`nio/` | 见下 | `exit=0`，生成 27+N 个 class |
| S2 编译 PC 全量源码 | 见 §9.4 脚本前两条 | `exit=0` |
| S3 两份 `core/` MD5 一致 | 见 §9.3 | 全部 `same=True` |
| S4 AIDL 事务号唯一且未被占用 | `grep -n "= [0-9]" IIOService.aidl` | 新增 `getFileLength = 14`，与 1..13/16777114 不冲突 |
| S5 `core/` 无平台专属 API | 见 §7.5 第 5 条 grep | 0 命中 |
| S6 Android 改动文件语法可解析 | `javac -proc:none -nowarn -d %TEMP%\o <改动文件>`；**只看错误类型** | 只允许出现 `package android.*/androidx.*/R does not exist`、`cannot find symbol`、`method does not override`；**不允许** `expected`/`illegal start`/`reached end of file` 这类语法错误 |

S1 的 shim（`org.jetbrains.annotations.NotNull` 是 IDE 提供的库，仓库里没有 jar；**这是编译期注解，用一个 6 行 shim 即可**）：

```powershell
$tmp = Join-Path $env:TEMP 'hfx-acheck'
Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory (Join-Path $tmp 'shim\org\jetbrains\annotations') -Force | Out-Null
New-Item -ItemType Directory (Join-Path $tmp 'classes') -Force | Out-Null
$shim = "package org.jetbrains.annotations;`nimport java.lang.annotation.ElementType;`nimport java.lang.annotation.Retention;`nimport java.lang.annotation.RetentionPolicy;`nimport java.lang.annotation.Target;`n@Retention(RetentionPolicy.CLASS)`n@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE})`npublic @interface NotNull {}`n"
[IO.File]::WriteAllText((Join-Path $tmp 'shim\org\jetbrains\annotations\NotNull.java'), $shim, (New-Object Text.UTF8Encoding $false))
$base = 'D:\2Code\HybridFileXfer\HybridFileXfer-Android\app\src\main\java\top\weixiansen574'
$files = @(Get-ChildItem "$base\hybridfilexfer\core" -Recurse -Filter *.java | % FullName) +
         @(Get-ChildItem "$base\nio" -Recurse -Filter *.java | % FullName) +
         @((Join-Path $tmp 'shim\org\jetbrains\annotations\NotNull.java'))
& javac -encoding UTF-8 -nowarn -d (Join-Path $tmp 'classes') @files
"exit=$LASTEXITCODE"
```

> `-encoding UTF-8` **必须**：源码含中文注释，中文 Windows 上 javac 默认按 GBK 解码会直接报错。

### 9.2 PC 侧最小测试用例（可执行，必须实现）

放在 `HybridFileXfer-PC/test/`（**不进 `src/`**）：

```
test/shim/org/jetbrains/annotations/NotNull.java          # 6 行 compiled-only 注解 shim
test/top/weixiansen574/hybridfilexfer/ResumeSelfTest.java # 全部用例，main() 顺序执行
test/run-resume-selftest.ps1
```

`ResumeSelfTest.java` **纯 ASCII**（含中文路径测试时用 `"\u4e2d\u6587"` 转义），`main` 顺序跑下表，任一失败 `System.exit(1)` 并打印 `FAIL: <case> <detail>`；全部通过打印 `SELFTEST PASS (7/7)`。

| 用例 | 断言（**逐条验收**） |
| --- | --- |
| **T1 BlockBitmap** | `byteLength(1)==1`、`byteLength(8)==1`、`byteLength(9)==2`、`byteLength(0)==0`；新建对象 `hasCompleted()==false`；`setCompleted(8,true)` 后 `isCompleted(8)` 且 `toBytes()[1]==0x01`；`fromBytes(9, new byte[1])` 抛 `IllegalArgumentException`；`isAllCompleted()` 仅在全部置位后为真；填充位（bit 9..15）不影响 `isAllCompleted` |
| **T2 Crc32** | `Crc32.compute(ByteBuffer.wrap("123456789".getBytes(UTF_8)),0,9) == 0xCBF43926`；对 1MB 随机数据的随机 `offset/length` 与 `java.util.zip.CRC32` 结果逐一相等；调用前后 `buffer.position()/limit()` 不变 |
| **T3 ResumeTaskKey** | 同一集合换个顺序 → 键相同；顶层文件 size 改 1 → 键不同；目录 mtime 改 → 键相同（目录不参与）；键为 32 位小写 hex；空集合可用且与"只有一个空目录"的集合**不同**（目录路径参与） |
| **T4 协商编解码** | 用 `loopback SocketChannel` 造一对 `DataByteChannel`；写 `RESUME_QUERY`（含 3 个 entry，其中一个 `destPath` 含 `\u4e2d\u6587`、含反斜杠）→ `readQuery` 逐字段相等（含顺序）；写 `RESUME_REPLY`（含 2 条，bitmap 长度非 8 的整数倍，如 blockCount=9/17）→ `readReply` 返回的 `Map` 键值与原条目一致；故意写错消息号 → 抛 `IOException`；`byteLen` 与 `blockCount` 不符 → 抛 `IOException` |
| **T5 ReadFileCall 跳过集** | 临时目录建 3.5MB 源文件（`byte i = (byte)(i*31+7)`）；`JdkReadFileCall` + `ResumeSkipSet{block1}` → 依次取出块序号恰为 `[0,2,3]`，长度 `[1048576,1048576,524288]`，且块 2 的内容与源文件 `offset=2MB` 处逐字节相同；`ResumeSkipSet` 全置位 → 只取到 1 个 `END_POINT`（无数据块）；`EMPTY` → 4 个块且内容全对；0 字节文件 → 1 个长度 0 的块 |
| **T6 WriteFileCall 续传写** | 目标文件预先填满 `0xFF` 且 `setLength(3.5MB)`；只投递块 `[0,2,3]` 后 `finishChannel`；断言 `[0,1MB)`、`[2MB,3MB)`、`[3MB,3.5MB)` 与源一致，`[1MB,2MB)` 仍为 `0xFF`；再跑一次全量 4 块 → 整文件与源逐字节相同 |
| **T7 JdkResumeStore** | 测试内 `System.setProperty("hfx.resume.dir", <临时目录>)`；存 2 条（其中 `localPath` 含 `\`、`"`、`\u4e2d\u6587`、`\u0001`）→ `loadTask` 全字段相等；`removeEntry` 后剩 1 条；`removeTask` 后 0 条；写入 `updatedAt=0` 的条目后 `purgeExpired(now, 7d)` 清掉；**故意把 json 写坏** → `loadTask` 返回空表且不抛异常 |

**（可选，不计入验收）T8 端到端**：环回 socket 上跑真实 `SendFileCall`+`ReceiveFileCall`+`JdkReadFileCall`+`JdkWriteFileCall`（含 CRC 开/关、断线后续传），断言"第二次只传缺失块、最终字节一致"。若时间不足可放弃，但要记录为未覆盖项。

### 9.3 一致性校验命令

```powershell
$a = 'D:\2Code\HybridFileXfer\HybridFileXfer-Android\app\src\main\java\top\weixiansen574\hybridfilexfer\core\'
$b = 'D:\2Code\HybridFileXfer\HybridFileXfer-PC\src\top\weixiansen574\hybridfilexfer\core\'
$bad = 0
Get-ChildItem $b -Recurse -Filter *.java | ForEach-Object {
    $rel = $_.FullName.Substring($b.Length); $p = $a + $rel
    if (-not (Test-Path $p) -or (Get-FileHash $_.FullName -Algorithm MD5).Hash -ne (Get-FileHash $p -Algorithm MD5).Hash) {
        "DIFF: $rel"; $script:bad++ }
}
Get-ChildItem $a -Recurse -Filter *.java | ForEach-Object {
    $rel = $_.FullName.Substring($a.Length)
    if (-not (Test-Path ($b + $rel))) { "A-ONLY: $rel"; $script:bad++ }
}
"core parity bad=$bad"; if ($bad -ne 0) { exit 1 }
# nio 同样比对
```

### 9.4 运行脚本 `HybridFileXfer-PC/test/run-resume-selftest.ps1`

```powershell
$ErrorActionPreference = 'Stop'
$proj = Split-Path -Parent $PSScriptRoot
$out  = Join-Path $proj 'build-resume-selftest'
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory (Join-Path $out 'classes') -Force | Out-Null
New-Item -ItemType Directory (Join-Path $out 'test-classes') -Force | Out-Null
$classes = Join-Path $out 'classes'; $tclasses = Join-Path $out 'test-classes'

$src  = @(Get-ChildItem (Join-Path $proj 'src') -Recurse -Filter *.java | % FullName)
$shim = Join-Path $PSScriptRoot 'shim\org\jetbrains\annotations\NotNull.java'
& javac -encoding UTF-8 -nowarn -d $classes @($src + $shim)
if ($LASTEXITCODE -ne 0) { throw 'production compile failed' }

$tests = @(Get-ChildItem $PSScriptRoot -Recurse -Filter *.java | ? { $_.FullName -notlike '*\shim\*' } | % FullName)
& javac -encoding UTF-8 -nowarn -cp $classes -d $tclasses @tests
if ($LASTEXITCODE -ne 0) { throw 'test compile failed' }

& java -cp "$classes;$tclasses" top.weixiansen574.hybridfilexfer.ResumeSelfTest
if ($LASTEXITCODE -ne 0) { throw "SELFTEST FAIL (exit=$LASTEXITCODE)" }
Write-Host 'SELFTEST PASS'
```

运行：`powershell -ExecutionPolicy Bypass -File D:\2Code\HybridFileXfer\HybridFileXfer-PC\test\run-resume-selftest.ps1`
（等价手工命令：`javac -encoding UTF-8 -nowarn -d out ...` → `javac -encoding UTF-8 -cp out -d out2 ...` → `java -cp "out;out2" top.weixiansen574.hybridfilexfer.ResumeSelfTest`）

### 9.5 验证边界（**禁止越界声明**）

* Android 侧：**只能**做到 S1/S3/S4/S5/S6（编译 + 结构 + 语法）。`AndroidResumeStore`、`ConfigDB` 升级、`DroidWriteFileCall.lengthOfFile`、AIDL 事务、前台服务、watchdog **全部未经运行验证**；
* 不得把"javac 语法通过"写成"编译通过/功能验证通过"；
* 不得用"PC 侧通过"推断 Android 侧通过（二者共享 `core/`，但平台适配层完全未测）；
* 交付说明必须原样写出未验证清单（照抄本节）。

---

## 10. 要求 9：风险与边界

| # | 风险 | 处置 / 契约规定 |
| --- | --- | --- |
| R1 | **断点期间源文件被改动** | 顶层文件参与 `taskKey`（size+mtime）→ 变则换键，旧清单失效；树内文件变了 → 条目 `blockCount`/`totalSize` 不匹配 → `ResumeSkipSet` 判为"不可信"→ 全量重发。**不做**静默续传 |
| R2 | **目标文件在断点期间被删/被改** | 协商阶段 `queryFileLength != totalSize` → 清位 + 删行 + 不回执 → 整文件重传（防"前半截全零"的坏文件） |
| R3 | **部分块 / 末块 / 0 字节文件** | 末块长度按 §5.3 计算；0 字节文件固定 1 块（长度 0）；bitmap 填充位必须为 0，读取端不得依赖填充位 |
| R4 | **多通道并发的位图竞争** | 位图只由 `WriteFileCall` 单写线程修改；`ReceiveFileCall`（多线程）只负责读块 + 入队，**不得**直接改位图。`ResumeSession` 只在写线程内被调用（`abortFile` 例外：由 `ReceiveFileCall` 在抛异常前调用，此时写线程已在异常路径上，实现里对 `ResumeSession` 的所有公开方法加 `synchronized`） |
| R5 | **CRC 冲突** | CRC32 只是"传输/落盘错误检测"，非防篡改；漏检概率 ~2^-32/块。默认关闭；关闭时完全无保护 |
| R6 | **Shizuku/Sui/root 不可用** | 保活降级：无前台服务权限/被 ROM 拦 → 传输照旧（只是易被杀）；无 root → 无 watchdog（静默）；AIDL 变更后若 daemon 未重建（未 bump version）→ `TransactionException`/`BadParcelable`，**所以 `.version(2)` 是硬要求** |
| R7 | **目录 mtime 变化** | 目录 mtime 不参与 `taskKey`（否则续传几乎永远失效）；代价是"目录里新增文件"不会使键失效，靠逐文件位图兜住（新文件无位图 → 全传） |
| R8 | **新旧版本互连** | `VERSION_CODE 301` 与 300 直接拒绝连接（既有校验），必须两端同时升级；交付说明要写升级提示 |
| R9 | **PC 手写 JSON 的健壮性** | 严格转义子集 + 原子替换 + 解析失败即删文件降级；T7 覆盖反斜杠/引号/中文/控制字符 |
| R10 | **断电窗口** | 清单落盘前必 `force(false)`（§3.6）；仍存在"写完块但还没走到 force 就断电"的窗口 → 该块位不会被记，最坏重传 |
| R11 | **`DataByteChannel.write(ByteBuffer)` 可能部分写（既有缺陷）** | 本轮只对**新增**的位图写用 `writeFully`；块数据传输的既有部分写风险**记录但不改**（属另一处重构，避免与协议改造叠加） |
| R12 | **暂停期间对端断线不被及时发现** | 用户"继续"或取消后立刻暴露；可接受（§6.5）。**但**"暂停中本端主动断开/退出"必须由 `abortTransfer()` 立即唤醒（§6.3），否则线程与缓冲永久泄漏 |
| R15 | **暂停 + 断开的组合会挂死传输线程** | 已用 `abortTransfer()`（§6.3 三处调用点）+ `TransferGate.abort()` 覆盖；评审必须逐条核对这三个调用点是否真的加了 |
| R13 | **前台服务生命周期与传输不同步（P1-1）** | 见 §8.4；本轮不修，必须在交付说明写明 |
| R14 | **清单膨胀** | 只有"至少写过 1 块"的文件才建行；成功即删任务；7 天过期清理；单任务行数上限受选择集大小天然约束 |

---

## 11. 假设与待决事项

### 11.1 假设（若不成立需回头改契约）

1. "同一批任务" = **同一次顶层选择**（同源根、同目标根、同顶层条目集合），不要求跨次"半途改变选择"仍能续传；
2. 传输方向两侧都使用**本仓库同一份 `core/`**（301 协议），不支持与第三方实现互通；
3. 目标端可写且允许 `setLength(totalSize)` 预分配（现有实现已依赖）；
4. 用户接受"顶层文件被改动 = 重新全传"的保守语义；
5. CRC 默认关（性能优先），可由用户/参数开启。

### 11.2 待决事项（需 lead / 用户确认，不阻塞 task-3/4）

| # | 事项 | 本契约的默认动作 |
| --- | --- | --- |
| Q1 | 是否需要"续传任务列表 UI"（手动清理/查看断点） | 本轮**不做**：仅 7 天自动过期 + 成功即删 |
| Q2 | P1-1（`AppCore` 让服务器脱离 Activity 常驻）是否本轮一并做 | **不做**，单列 P1；本轮只保证 gate/session 是会话级对象，不挖坑 |
| Q3 | 通知是否实时显示传输进度 | 本轮**不改**：保持 task-2 的静态文案（改它需要跨 Activity 的进度通道，属 P1 范围） |
| Q4 | 是否给 PC 加交互式暂停（当前只有 `--crc`） | 不做；门控能力在 `core` 内已具备，将来加 CLI 即可 |

---

## 附录 A：新增/修改文件总表

**新增（core，两份）**：`BlockBitmap.java`、`ResumeSkipSet.java`、`ResumeEntry.java`、`ResumeStore.java`、`ResumeSession.java`、`ResumeTaskKey.java`、`ResumeProtocol.java`（含 `ResumeQuery`）、`TransferGate.java`、`Crc32.java`
**修改（core，两份）**：`HFXService.java`、`ControllerIdentifiers.java`、`FileBlock.java`、`ReadFileCall.java`、`WriteFileCall.java`、`SendFileCall.java`、`ReceiveFileCall.java`、`HFXClient.java`（仅 `handleShutdown()` 加 `abortTransfer()`）
**修改（nio，两份）**：`DataByteChannel.java`
**Android 新增**：`droidcore/AndroidResumeStore.java`
**Android 修改**：`aidl/.../IIOService.aidl`、`IOServiceImpl.java`、`IOService.java`、`droidcore/HFXServer.java`、`droidcore/DroidHFXClient.java`、`droidcore/DroidWriteFileCall.java`、`ConfigDB.java`、`Config.java`、`MainActivity.java`、`ClientActivity.java`、`TransferActivity.java`、`TransferDialog.java`、`res/menu/main_menu.xml`、`res/values/strings.xml`、`res/values-zh/strings.xml`、`app/build.gradle`
**PC 新增**：`jdkcore/JdkResumeStore.java`、`test/**`（shim + 自测 + 运行脚本）
**PC 修改**：`jdkcore/JdkHFXClient.java`、`jdkcore/JdkWriteFileCall.java`、`Main.java`
**文档**：`docs/design-resume-keepalive.md`（本文）

## 附录 B：常量速查

| 常量 | 值 |
| --- | --- |
| `HFXService.VERSION_CODE` | `301` |
| `ControllerIdentifiers.RESUME_QUERY / RESUME_PLAN_END / RESUME_REPLY` | `20 / 21 / 22` |
| `IIOService.getFileLength` 事务号 | `14` |
| `FileBlock.BLOCK_SIZE` | `1048576` |
| `ResumeTaskKey.KEY_PREFIX / KEY_BYTES` | `"HFX-RESUME-V1" / 16` |
| `ResumeSession.FLUSH_BLOCK_INTERVAL / RETENTION_MS` | `32 / 604800000` |
| `ConfigDB.VERSION` | `2` |
| SharedPreferences 键 | `"block_crc"`（默认 false） |
| 位图字节长度 | `(blockCount + 7) / 8` |
| 位序 | `bytes[i>>>3]` 的 `1 << (i & 7)`（LSB 优先） |
| PC 清单目录 | `-Dhfx.resume.dir` 或缺省 `~/.hybridfilexfer/resume` |
| Android 清单表 | `resume_manifest`（库 `config`） |
