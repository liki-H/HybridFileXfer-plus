# 需求B（Android 侧）实现记录 — block 级续传 / CRC32 / 暂停恢复

> 任务：task-3（executor）。实现依据：`docs/design-resume-keepalive.md`（契约，唯一依据）。未改 PC 专属层
> （jdkcore / Main.java / PC 测试）以外的任何 PC 文件；`core/` 与 `nio/DataByteChannel.java` 两份逐字节相同（契约 §1.1）。
> 环境：**本机无 Android SDK**（无 `android.jar` / 无 `local.properties` / 无 `ANDROID_HOME`），Android 侧只做源码 + javac 语法自检，
> 契约 §9.5 的"未验证清单"照抄在第 6 节。

## 1. 改动文件清单

### 1.1 core/（两份逐字节相同：Android 先改 → 复制到 PC → MD5 校验）

新增（9）：

| 文件 | 内容 |
| --- | --- |
| `core/BlockBitmap.java` | 位图，`byteLength=(n+7)/8`，bit i 在 `bytes[i>>>3]` 的 `1<<(i&7)`（LSB 优先），padding 位归一化为 0 |
| `core/ResumeSkipSet.java` | 跳过集：`isSendable / isFileCompleted`（blockCount 不匹配即"不信任"） |
| `core/ResumeEntry.java` | 清单一行（10 字段，全 final，构造即校验） |
| `core/ResumeStore.java` | 清单存储接口 |
| `core/ResumeSession.java` | 会话级清单：内存行 + 32 块周期落盘 + `abortFile` + `markTaskCompleted` + 7 天过期清理 |
| `core/ResumeTaskKey.java` | taskKey（顶层选择集 → 排序去重 → SHA-256 前 16 字节 → 32 hex），含 `Entry` |
| `core/ResumeProtocol.java` | 协商编解码（`writeQuery/readQuery/writeReply/readReply`）+ 嵌套 `ResumeQuery` 载体 |
| `core/TransferGate.java` | 暂停/恢复/中止门控（含 `NOOP`、`abort()`） |
| `core/Crc32.java` | 自实现 CRC32（绝对索引，不改 position/limit；不用 Java 9の `update(ByteBuffer)`） |

修改（8）：

| 文件 | 改动 |
| --- | --- |
| `core/HFXService.java` | `VERSION_CODE 300→301`；`blockCrcEnabled`+`setBlockCrcEnabled`；`currentGate`+`getCurrentGate`+`abortTransfer()`；`sendFiles` 开头发 query / 读回执并注入 skipSet+crc+gate；`receiveFiles` 读 query / 用自算 taskKey 查清单 / 发回执 / 成功即删；新增 `protected abstract ResumeStore getResumeStore()`；两方法 `finally` 里 `gate.abort()` |
| `core/ControllerIdentifiers.java` | `RESUME_QUERY=20`、`RESUME_PLAN_END=21`、`RESUME_REPLY=22` |
| `core/FileBlock.java` | 新增 `public static int calcBlockCount(long)`（0 字节=1 块）；旧实例方法原样保留且无人调用 |
| `core/ReadFileCall.java` | skipSet/crcEnabled/gate 注入与 getter；`readToDeque` 重写为「按下标 + `channel.position()` 定位」；目录块永远重发；整文件完成不读盘；3 处 `awaitUnpaused`；修 EOF 死循环 |
| `core/WriteFileCall.java` | gate/resumeSession/crcEnabled + `abortResumeFile/touchFile/queryFileLength` + `protected abstract lengthOfFile`；写块后 `onBlockWritten`、每 32 块 `force+flush`；文件收尾「force→close→setLastModified→flush」；块循环顶部门控 |
| `core/SendFileCall.java` | 取块前门控（abort → `END_OF_INTERRUPTED` + INTERRUPT 回调）；`crcEnabled` 时在 `int length` 之后写 `int crc32`（绝对索引算，data 未 flip） |
| `core/ReceiveFileCall.java` | 读块头前门控；按 `writeFileCall.isCrcEnabled()` 读 `int crc32`；块数据读完后做 index 越界校验 + CRC 校验（失败 → `abortResumeFile` + 抛 IOException，不写盘） |
| `core/HFXClient.java` | 仅 `handleShutdown()` 开头加 `abortTransfer()` |
| `nio/DataByteChannel.java` | 追加 `writeFully(ByteBuffer)` / `writeFully(byte[])`（避免 SocketChannel 部分写） |

### 1.2 Android 侧

| 文件 | 改动 |
| --- | --- |
| `aidl/.../IIOService.aidl` | 追加 `long getFileLength(String path) = 14;`（1..13 与 16777114 均已占用，14 未用） |
| `IOServiceImpl.java` | 实现 `getFileLength`：`f.isFile() ? f.length() : -1` |
| `IOService.java` | `getUserServiceArgs(...).version(1) → version(2)`（AIDL 变更后旧 daemon 必须重建，否则 TransactionException） |
| `droidcore/DroidWriteFileCall.java` | 实现 `lengthOfFile(path)` → `ioService.getFileLength(path)` |
| `droidcore/AndroidResumeStore.java` | **新增**：`resume_manifest` 表 CRUD；任何 SQLite 异常 → `broken=true` 后续全 no-op（只打印一次栈），绝不拖垮传输 |
| `droidcore/HFXServer.java` | `attachContext(Context)` + `getResumeStore()`（未注入返回 null → 降级无续传）；`disconnect()` 与 `closeServerSocket()` 开头 `abortTransfer()` |
| `droidcore/DroidHFXClient.java` | `getResumeStore()`（复用已有 `context` 字段） |
| `ConfigDB.java` | `VERSION 1→2`；`resume_manifest` 建表 + `idx_resume_updated` 索引；`onUpgrade(oldVersion<2)` **真实现** |
| `Config.java` | `isBlockCrcEnabled()/setBlockCrcEnabled()`（键 `"block_crc"`，默认 false） |
| `MainActivity.java` | 菜单 `block_crc` 切换（checkable，onCreateOptionsMenu 回填状态，切换即同步到当前 `server`）；`onServiceConnected` 里 `attachContext(getApplicationContext())` + `setBlockCrcEnabled(config.isBlockCrcEnabled())`；Toast 文案走 strings |
| `ClientActivity.java` | `onServiceConnected` 里 `client.setBlockCrcEnabled(Config.getInstance(context).isBlockCrcEnabled())`（在 `ConnectServerTask` 之前） |
| `TransferDialog.java` | 构造参数追加 `Supplier<TransferGate>`（保留 3 参构造）；`show()` 增中性按钮「暂停/继续」，点击 `gate.pause()/resume()` 并改文案；`setCloseBtnEnable(true)` 时禁用该按钮 |
| `TransferActivity.java` | `gateSupplier()`（匿名 Supplier → `server.getCurrentGate()`）传给 `TransferDialogHandler` |
| `res/menu/main_menu.xml` | 追加 checkable 项 `@+id/block_crc` |
| `res/values/strings.xml` / `values-zh/strings.xml` | 追加 `block_crc` / `block_crc_on` / `block_crc_off` / `pause` / `resume` |
| `app/build.gradle` | `versionCode 300 → 301`（与协议版本对齐） |
| PC `core/` + `nio/` | 由 Android 侧复制而来（逐字节相同，见 §3 S3） |

未改：`foreground/`（task-2 成果）、`AndroidManifest.xml`、`P1-1` 相关路径、PC 的 `jdkcore/`、`Main.java`、`test/` 的 T5–T7（task-4）。

## 2. 关键实现点（契约逐条对齐）

1. **协商顺序**：永远发送方先发 `RESUME_QUERY`（20）→ 条目 → `RESUME_PLAN_END`（21）；接收方回 `RESUME_REPLY`（22）+ 位图。读写只出现在 `ResumeProtocol`。
2. **taskKey**：顶层选择集 → `generateTransferPath` 生成 destPath（与块路径同一函数）→ 目录 size/mtime 记 0 → `String.compareTo` 升序 → 相同 path 去重 → `SHA-256(prefix+count+每条)` 取前 16 字节转 32 位小写 hex。接收端**用自己算的键**查清单，不信对端值。
3. **位图**：`byte[ceil(blockCount/8)]`，LSB 优先；`fromBytes` 归一化 padding 位；`readReply` 强校验 `blockCount∈[1,4194304]` 与 `byteLen==byteLength(blockCount)`。
4. **CRC32**：`int`，紧跟 `int length`、`data` 之前；`crcEnabled==false` 时**不写不读不算**（零开销）；失败路径：不写盘 → `abortResumeFile`（清该文件位 + 删行）→ 抛 IOException → 走既有 `onChannelError` → 整批中止、下轮整文件重传。
5. **接收端回执**：逐行 `queryFileLength != totalSize` → `abortFile` + 不回执；无已完成块 → 不回执；全完成 → 修 mtime 后回执。
6. **跳过集**：`isSendable` 是唯一判定；整文件完成则连缓冲都不 checkout、不读盘；目录块永远重发。
7. **暂停**：纯本地 `TransferGate`，门控只在 4 个循环顶部的块边界；`awaitUnpaused()` 返回 false（abort）时各走既有结束分支。
8. **强制唤醒**：`HFXService.abortTransfer()` 在 `HFXClient.handleShutdown()`、`HFXServer.disconnect()`、`HFXServer.closeServerSocket()` 三处都调用（契约 R15，缺一个就会在"暂停中停止/退出"时永久挂死）。
9. **清单**：只在接收端；Android 复用 `ConfigDB`（`config` 库，`resume_manifest` 表，`PRIMARY KEY (task_key, local_path)`，列名 snake_case ↔ `ResumeEntry` 字段一一对应）；落盘前先 `force(false)`；会话成功 → `removeTask`；失败 → `flush` 保住已完成块；7 天过期。
10. **AIDL**：新增 `getFileLength = 14`（`RemoteException` 按现有惯例 `throws Exception`），`UserServiceArgs.version(2)`。

## 3. 已跑的自检 / 测试（命令 + 结果）

| # | 内容 | 命令 | 结果 |
| --- | --- | --- | --- |
| S1 | Android `core/`+`nio/` 脱离 SDK 编译 | `javac -encoding UTF-8 -nowarn -d %TEMP%\hfx-acheck2\classes <core全量> <nio全量> <NotNull shim>` | **exit=0，35 个 class** |
| S2 | PC 全量源码编译 | `powershell -ExecutionPolicy Bypass -File HybridFileXfer-PC\test\run-resume-selftest.ps1`（内部先 javac 全量 35 文件） | **task-4 完成：exit=0，45 个 class，T1–T7 全 PASS**，详见 `docs/resume-notes-pc.md` |
| S3 | 两份 `core/` MD5 一致 | 递归比对 + A-ONLY 检查 | **parity bad=0，A=28 / B=28 文件**；`nio/DataByteChannel.java` MD5 相同 |
| S4 | AIDL 事务号唯一 | grep `= <num>` | 新增 `getFileLength = 14`，与 1..13、16777114 无冲突 |
| S5 | `core/` 无平台专属 API | grep `import android.` / `java.util.Base64` / `java.util.zip` / `List.of(` / `String.repeat(` / `var x =` | **全部 0 命中**（`RESUME_QUERY` 字面量只出现在 `ControllerIdentifiers` 与 `ResumeProtocol`） |
| S6 | Android 非 core 改动文件语法可解析 | `javac -proc:none -nowarn -Xmaxerrs 5000` 13 个文件 | 1088 条错误全部是 `package … does not exist` / `cannot find symbol` / 缺父类型派生的 `method does not override`；**非符号类错误 0 条** |
| T1–T4 | 契约 §9.2 用例 | `powershell -ExecutionPolicy Bypass -File HybridFileXfer-PC\test\run-resume-selftest.ps1 -CoreOnly` | `PASS T1 BlockBitmap / PASS T2 Crc32 / PASS T3 ResumeTaskKey / PASS T4 resume codec` → `SELFTEST PASS (4/4)` |
| T5–T7 | 跳过集实读、续传写、JdkResumeStore | 需要 `jdkcore` 适配 | **task-4 已完成并全部 PASS（7/7）**，详见 `docs/resume-notes-pc.md` §3 |

> `-CoreOnly` 只编译 `core/`+`nio/`+shim+测试（29 个生产文件），因为 PC 的 `JdkWriteFileCall/JdkHFXClient` 尚未实现
> 新增的抽象方法（`lengthOfFile` / `getResumeStore`），全量编译属 task-4 范围。

### 3.1 测试期间修掉的问题（如实记录）

* 首跑 T1 失败：**测试断言写错**（构造 `fromBytes(9, {0xFF,0xFE})` 时 bit 8 未置位，`isAllCompleted` 正确返回 false）。实现无误，已把用例改为 `{0xFF,0xFF}` 并追加"`fromBytes` 必须把 padding 位归一化为 0"的断言；复跑全绿。

## 4. 两端一致性核对（契约 §7.5 九条）

1. `core/` 逐文件 MD5 相同（28 文件）：**PASS**（parity bad=0）
2. `VERSION_CODE == 301` 两处相同：**PASS**
3. `ControllerIdentifiers` 20/21/22 相同：**PASS**
4. 消息读写只在 `ResumeProtocol`（`RESUME_QUERY` 字面量仅 `ControllerIdentifiers`/`ResumeProtocol`）：**PASS**
5. `core/` 无 Android/PC 专属 API：**PASS**
6. 位序只在 `BlockBitmap` 定义（`1<<(i&7)` 仅出现在 `BlockBitmap`）：**PASS**
7. 默认值一致（`blockCrcEnabled=false`、`crcEnabled=false`、`ResumeSkipSet.EMPTY` 全发）：**PASS**
8. `DataByteChannel` 两份 MD5 相同：**PASS**
9. 清单字段映射（SQLite snake_case ↔ JSON camelCase ↔ `ResumeEntry` 字段）：Android 侧已按 §3.1 落地；PC 侧 JSON 属 task-4，届时逐项对照

## 5. 已知限制 / 决策记录

1. **P1-1 仍在**（契约 §8.4 追认，本轮不修）：服务器生命周期仍与 `MainActivity` 实例绑定（`onDestroy` 里 disconnect + unbind + 停前台服务）。用户若在传输中退出 Activity，传输仍会中断。本轮 `TransferGate`/`ResumeSession` 都是"每次 `sendFiles/receiveFiles` 新建"的会话级对象，不会给 P1 挖坑。
2. `ResumeSession` 额外提供 `setRemoteRoot(String)`（契约签名之外的**纯增量**），用于把 query 里的 `senderRoot` 写进清单 `remote_path` 列（§3.1 要求"同一任务所有行相同"）。构造签名保持契约原样。
3. `ResumeProtocol.ResumeQuery` 作为 `ResumeProtocol` 的**嵌套静态类**（Java 单文件只能有一个 public 顶层类；契约 §7.1 也是"ResumeProtocol.java（含 ResumeQuery）"）。
4. 暂停期间对端断线**不会立即发现**（线程阻塞在门控上，契约 §6.5 已接受该代价）；但"暂停中本端停止/退出"由 `abortTransfer()` 三处调用点保证不挂死。
5. `WriteFileCall.cancel()` 只回收队列中未写的块；本实现的门控点放在"处理完当前块、`takeBlock()` 之前"，因此不会出现"手里攥着一个块不回收"的泄漏。

## 6. 未验证项（照抄契约 §9.5，需真机/后续任务）

> Android 侧：**只能**做到 S1/S3/S4/S5/S6（编译 + 结构 + 语法）。`AndroidResumeStore`、`ConfigDB` 升级、`DroidWriteFileCall.lengthOfFile`、AIDL 事务、前台服务、watchdog **全部未经运行验证**；不得把"javac 语法通过"写成"编译通过/功能验证通过"；不得用"PC 侧通过"推断 Android 侧通过。

补充本轮特有未验证项：

1. `IIOService.getFileLength` 事务号 14 与旧客户端/新 daemon 混用（依赖 `.version(2)` 让 Shizuku 重建进程）——真机验证。
2. `ConfigDB` v1→v2 升级路径（真机升级安装后 `resume_manifest` 是否创建）。
3. 断点续传端到端（Android↔PC、Android↔Android）实际只传缺失块、最终字节一致——需真机 + task-4 完成后联调。
4. 暂停/继续按钮在真实传输中的行为（暂停后对端背压、恢复后无缝续传）、暂停中"停止服务/退出"不挂死。
5. 多通道并发下位图单写线程语义（R4）在真机的时序表现。
6. 断电/强杀场景下清单"不过度上报"（`force` 后落盘）——真机或模拟断电极难验证，仅代码层保证顺序。
