# 独立审查报告（task-5）：保活（需求C）+ block 级续传（需求B）Android / PC

> 审查者：reviewer（独立审查，**只读实现，未修改任何生产代码**）
> 审查对象：task-2 / task-3 / task-4 的全部实际改动（工作区未提交）
> 唯一实现依据：`docs/design-resume-keepalive.md`（下称"契约"）
> 基线：`main` @ `14e75ab`，工作区全部改动未 commit（`git log -1` 仍是上游 merge），无 `.git` 之外的第三方基线
> 证据原则：**下表所有命令与输出均由本审查者亲自重跑得到**，不采信执行者自述；执行者 notes 仅作为"待核对声明"处理

---

## 0. 结论摘要

| 项 | 结论 |
| --- | --- |
| **verdict** | **PASS**（可放行；无 blocking 缺陷） |
| 已证实缺陷（P0/P1） | **0** |
| 契约偏差（非阻断，需 lead 裁决是否补齐） | 2 条（F1/ F2，见 §3.2） |
| 待验证风险（Android 运行时，只能真机） | 4 条（F6/F7/F8/F11） |
| 文档/绑定问题 | 3 条（F3 产物 SHA 绑定过期、F4/F5 措辞与本地化） |
| "假通过/改绿" | **未发现**。PC 侧 T1–T7 真跑真断言（已逐行读测试源码 + 亲自复跑）；Android 侧执行者只声称 S1/S3/S4/S5/S6，未把"语法通过"写成"功能通过"（与契约 §9.5 一致） |
| Android 运行时 | 按契约 §9.5 **仍未验证**（无 SDK，无法 gradle 构建）；本报告新增的端到端验证**全部在 PC/共享 core 侧**，不得据此推断 Android 侧通过 |

**本报告额外产出（超出执行者范围）**：审查者自己写了一份 loopback 端到端集成测试，覆盖契约 §9.2 的**可选 T8**（执行者明确未做），
6/6 全绿，首次把"协商 → 回执 → 跳过集 → 真实 SendFileCall/ReceiveFileCall → 续传写盘 → 清单生命周期 → CRC 上流/失败路径"串成真实链路验证。
测试源码放在仓库外 `%TEMP%\hfx-itest\ResumeITest.java`（**未入库，不改动被测代码**）。

---

## 1. 变更清单与文件级摘要

### 1.1 规模

```
$ git -C D:\2Code\HybridFileXfer diff --shortstat
 43 files changed, 1474 insertions(+), 383 deletions(-)
 其中 Android 树 26 files  +901 / -201
      PC      树 17 files  +573 / -182
未跟踪（新增）：22 个文件 + 2 个目录（foreground/、test/）+ docs/（4 篇）
```

### 1.2 `core/` 与 `nio/`（两份逐字节相同，共享协议层）

| 文件 | 动作 | 我核对到的实质内容 |
| --- | --- | --- |
| `HFXService.java` | 改 | `VERSION_CODE = 301`；`blockCrcEnabled`(volatile)+setter/getter；`currentGate`+`getCurrentGate()`；`abortTransfer()`；`sendFiles()` 开头写 `RESUME_QUERY`→读回执→注入 skipSet/crc/gate；`receiveFiles()` 开头读 query→自算 taskKey→`buildResumeReply()`→写回执；两方法 `finally{ gate.abort(); currentGate=null; }`；`receiveFiles` finally 里"成功→`markTaskCompleted()`，否则→`flush()`"；新增 `protected abstract ResumeStore getResumeStore()` |
| `ControllerIdentifiers.java` | 改 | `RESUME_QUERY=20 / RESUME_PLAN_END=21 / RESUME_REPLY=22`（追加在 `REQUEST_SEND` 之后） |
| `FileBlock.java` | 改 | 新增 `public static int calcBlockCount(long)`（0 字节=1 块）；旧实例方法原样保留且**全仓库无调用点**（已 grep 确认） |
| `ReadFileCall.java` | 改 | skipSet/crcEnabled/gate 注入 + getter；`readToDeque` 重写为"按下标 + `channel.position()`"、整文件完成不 checkout 缓冲不读盘、目录块永远重发、修 EOF 死循环；3 处 `awaitUnpaused()`（文件循环顶 / 递归遍历顶 / 块循环顶，均在取缓冲之前） |
| `WriteFileCall.java` | 改 | gate/resumeSession/crcEnabled + `abortResumeFile/touchFile/queryFileLength/isCrcEnabled` + `protected abstract lengthOfFile`；写块后 `onBlockWritten` + 每 32 块 `force+flush`；文件切换与收尾均"force→close→setLastModified→flush"；块边界门控（中止→`cancel()`→break，尾部仍收尾） |
| `SendFileCall.java` | 改 | 取块前门控（abort→`END_OF_INTERRUPTED`+INTERRUPT 回调+break）；`crcEnabled` 时在 `int length` 之后、data 之前 `writeInt(Crc32.compute(data,0,data.position()))` |
| `ReceiveFileCall.java` | 改 | 读块头前门控；`crcEnabled` 时读 `int crc32`（位置与写端一致）；块数据读满后先做 `index` 越界校验，再做 CRC 校验（**失败→`abortResumeFile(path)`→抛 IOException，未 `putBlock`**） |
| `HFXClient.java` | 改 | 仅 `handleShutdown()` 开头 `abortTransfer()`（与契约 §7.1 一致） |
| `BlockBitmap.java` | 新 | `byteLength=(n+7)/8`；bit i 在 `bytes[i>>>3]` 的 `1<<(i&7)`（LSB）；`fromBytes` 校验长度并归一化 padding 位；越界读写忽略/false |
| `Crc32.java` | 新 | 自实现表驱动（0xEDB88320），绝对索引，不改 position/limit；无 `java.util.zip.CRC32.update(ByteBuffer)` |
| `ResumeTaskKey.java` | 新 | `KEY_PREFIX="HFX-RESUME-V1"`、`KEY_BYTES=16`；`fromSelection` 生成 destPath（与块路径同函数 `generateTransferPath`）→ 按 `String.compareTo` 升序 → 同 path 去重；目录 size/mtime 记 0；显式 UTF-8 序列化 + SHA-256 前 16 字节 → 32 位小写 hex |
| `ResumeProtocol.java` | 新 | 唯一读写点；`writeQuery/readQuery/writeReply/readReply`（`ResumeQuery` 为嵌套静态类）；`entryCount/replyCount` 越界、`blockCount∉[1,4194304]`、`byteLen != byteLength(blockCount)`、错误消息号、未终止 plan 全部抛 `IOException`；位图用 `writeFully/readFully` |
| `ResumeSkipSet.java` | 新 | 防御性拷贝；`isSendable` 唯一判定（bitmap 缺失或 blockCount 不匹配→要发）；`EMPTY`=全发 |
| `ResumeEntry.java` | 新 | 10 字段全 final，构造即校验（taskKey/remotePath/localPath/bitmap 非 null） |
| `ResumeStore.java` | 新 | 契约 §3.4 的 5 方法接口 |
| `ResumeSession.java` | 新 | 全部 public 方法 `synchronized`（R4）；行缓存 + `dirty`；`onBlockWritten` 在 blockCount 变化时重建位图；`abortFile` 只删本行并即时落盘（只擦不增）；`flush` 只写脏行；`markTaskCompleted`；`purgeExpiredIfNeeded` 每会话最多 1 次；`FLUSH_BLOCK_INTERVAL=32`、`RETENTION_MS=7d` |
| `TransferGate.java` | 新 | `NOOP` 单例；`pause/resume/abort/isPaused/isAborted/awaitUnpaused()`；`abort()` 幂等不可逆并 `notifyAll()`（解决"暂停中停止/退出"死锁） |
| `nio/DataByteChannel.java` | 改（两份） | 追加 `writeFully(ByteBuffer)` / `writeFully(byte[])`（`origin.write` 部分写循环 + EOF 检测）；原有 `readFully(ByteBuffer)` 保持 |

### 1.3 Android 平台层

| 文件 | 动作 | 摘要 |
| --- | --- | --- |
| `IIOService.aidl` | 改 | `long getFileLength(String path) = 14;`（1..13 与 16777114 均已占用，14 唯一） |
| `IOServiceImpl.java` | 改 | `getFileLength`：`isFile()?length():-1` |
| `IOService.java` | 改 | `UserServiceArgs.version(1)→(2)`；`ComponentName` 改用 ApplicationContext；`onDestroy` 空指针加固（启动式服务未绑定→`service==null`）；新增 `onStartCommand→START_STICKY` |
| `droidcore/HFXServer.java` | 改 | `attachContext(Context)`（null→降级）+ `getResumeStore()`（ctx 空返回 null）；`disconnect()` 与 `closeServerSocket()` 开头 `abortTransfer()` |
| `droidcore/DroidHFXClient.java` | 改 | `getResumeStore()`→`new AndroidResumeStore(context)`，`context==null` 返回 null |
| `droidcore/DroidWriteFileCall.java` | 改 | `lengthOfFile`→`ioService.getFileLength` |
| `droidcore/AndroidResumeStore.java` | 新 | `resume_manifest` CRUD；`ORDER BY local_path`；`CONFLICT_REPLACE`；任何异常→`broken=true` 后续全 no-op（只打印一次栈）；坏行（bitmap 长度不符）跳过 |
| `ConfigDB.java` | 改 | `VERSION=2`；`onCreate` 追加建表+索引；`onUpgrade` **真实现**（`oldVersion<2` 建表） |
| `Config.java` | 改 | `isBlockCrcEnabled/setBlockCrcEnabled`（键 `"block_crc"`，默认 false） |
| `MainActivity.java` | 改 | 菜单 `block_crc`（checkable，onCreateOptionsMenu 回填，点击即同步到当前 server + Toast）；`onServiceConnected`：`attachContext(getApplicationContext())` + `setBlockCrcEnabled(config.isBlockCrcEnabled())`；API33+ 请求 POST_NOTIFICATIONS；`appContext.bindService` + `bound` 标记；`onDestroy` 里 disconnect 回调 onComplete/onError 都解绑（原默认 onError 会 `throw RuntimeException`，属加固） |
| `ClientActivity.java` | 改 | 同上绑定侧；`client.setBlockCrcEnabled(Config.getInstance(context).isBlockCrcEnabled())` 在 `ConnectServerTask` 之前；`isRootMode = ioMode==1` |
| `TransferDialog.java` | 改 | 4 参构造（保留 3 参）；`show()` 加 neutral「暂停/继续」并覆盖其点击监听（不关闭对话框）；`togglePause()` 调 `gate.pause()/resume()` 并切文案；`setCloseBtnEnable(true)` 时禁用 neutral |
| `TransferActivity.java` | 改 | `gateSupplier()` → `server.getCurrentGate()` 传入 Dialog |
| `res/menu/main_menu.xml` | 改 | checkable `@+id/block_crc`（图标 `baseline_check_circle_24` 存在） |
| `res/values/strings.xml` / `values-zh/strings.xml` | 改 | 各 +11 条（5 条 keepalive_* + 6 条续传/暂停），两侧 key 完全一致 |
| `AndroidManifest.xml` | 改（task-2） | `WAKE_LOCK/FOREGROUND_SERVICE/FOREGROUND_SERVICE_DATA_SYNC/POST_NOTIFICATIONS`；`.foreground.TransferForegroundService`（`dataSync`、`exported=false`、`stopWithTask=false`、`tools:targetApi=34`，未指定 `android:process` 即主进程） |
| `foreground/TransferForegroundService.java` | 新（task-2） | `onCreate`→acquireWakeLock + startForeground；API29+ 带 `FOREGROUND_SERVICE_TYPE_DATA_SYNC`；API26+ 建渠道（IMPORTANCE_LOW、setShowBadge(false)，重复调用幂等）；`onDestroy`→release + `RootWatchdog.stop()`；`START_STICKY` + 静态 `lastRootMode` 兜底；`onBind` 返回 null；静态 `start/start(root)/stop` 全走 ApplicationContext + try/catch 降级 |
| `foreground/RootWatchdog.java` | 新（task-2） | `su -c id` 判 root（5s 超时，`available()` 轮询 + `exitValue()`，避开 API26 的 `isAlive/waitFor(timeout)`）；脚本写 `filesDir/hfx_root_watchdog.sh`（UTF-8 无 BOM），每 30s `pidof <pkg>:IOService` 缺失则 `am start-service`；pid 文件 + `/proc/<pid>/cmdline` 校验防误杀；无 root/异常→静默降级并复位 `requested` |
| `app/build.gradle` | 改 | `versionCode 300→301` |

### 1.4 PC 专属层

| 文件 | 动作 | 摘要 |
| --- | --- | --- |
| `jdkcore/JdkResumeStore.java` | 新 | `-Dhfx.resume.dir` 覆盖，缺省 `~/.hybridfilexfer/resume`；一任务一 JSON（键顺序固定、Base64 位图）；临时文件 + `ATOMIC_MOVE`（失败退 `REPLACE_EXISTING`）；手写 JSON 解析器（`\" \\ \/ \uXXXX \b\f\n\r\t`）；解析失败→删文件按无清单处理；异常→`broken` 全 no-op |
| `jdkcore/JdkWriteFileCall.java` | 改 | `lengthOfFile`：`exists()&&isFile()?length():-1` |
| `jdkcore/JdkHFXClient.java` | 改 | `getResumeStore()` 懒加载单例 |
| `Main.java` | 改 | `--crc`/`-crc` → `setBlockCrcEnabled(true)` + 提示；`-v` 额外打印 `protocol: <VERSION_CODE>` |
| `messages_*.properties` | 改 | 5 个语言包都补 `block_crc_enabled`（缺键会 `MissingResourceException`）；en/zh_CN 的 `help_pages` 补 `--crc` 行 |
| `test/**` | 新 | `ResumeSelfTest.java`（T1–T7，纯 ASCII，真实文件 IO）、`shim/.../NotNull.java`、`run-resume-selftest.ps1`（构建产物放 `%TEMP%`，不污染工作区） |

---

## 2. 验收项逐条 PASS/FAIL 与证据

### 2.1 A 组：两端一致性（契约 §7.5 九条，必须逐条打勾）

| # | 验收项 | 结果 | 证据（我亲自执行） |
| --- | --- | --- | --- |
| A1 | `core/` 逐文件 MD5 相同 | **PASS** | 双向递归 MD5 + A/B-ONLY 检查 → `core files android=28 pc=28 parity_bad=0`（注：契约写"18 老 + 9 新 = 27"，实际老文件 19 个，见 F12） |
| A2 | `VERSION_CODE==301` 两处 | **PASS** | `Select-String VERSION_CODE` → 两份均 `301` |
| A3 | 三个新常量 20/21/22 | **PASS** | 直接读 `ControllerIdentifiers.java`（两份相同） |
| A4 | 消息读写只在 `ResumeProtocol` | **PASS** | grep `RESUME_QUERY|PLAN_END|REPLY` → 只命中 `ControllerIdentifiers.java:13-17`、`ResumeProtocol.java:54/67/72/92/101/116`（`HFXService` 只调用 `ResumeProtocol`，未直接写消息号） |
| A5 | `core/` 无平台专属 API | **PASS** | grep `import android.|androidx.|java.util.Base64|List.of|String.repeat|var x =` → **0 命中**；`java.util.zip.CRC32` → **0 命中** |
| A6 | 位序只在 `BlockBitmap` | **PASS** | grep `1 << (` → 只有 `BlockBitmap.java:48/57` + 第 7 行注释；实现为 `bytes[i>>>3] & (1<<(i&7))`（LSB 优先），`fromBytes` 用 `(1<<usedBits)-1` 清 padding |
| A7 | 默认值一致 | **PASS** | `blockCrcEnabled=false`、`ReadFileCall.crcEnabled=false`、`ResumeSkipSet.EMPTY` 全发（T4 断言 `EMPTY.isSendable(...)==true`） |
| A8 | `DataByteChannel` 两份 MD5 相同 | **PASS** | `nio same` |
| A9 | 清单字段映射 snake_case ↔ camelCase ↔ `ResumeEntry` | **PASS** | 逐字段核对：`task_key/taskKey`、`direction/direction`、`remote_path/remotePath`、`local_path/localPath`、`total_size/totalSize`、`last_modified/lastModified`、`block_count/blockCount`、`bitmap/bitmap`、`completed/completed`、`updated_at/updatedAt`（AndroidResumeStore:53-57/102-111、JdkResumeStore:354-362、ResumeEntry:11-26） |
| A10 | 旧实例方法 `calcBlockCount()` 不许调用 | **PASS** | grep `\.calcBlockCount\(\)` → **0 命中** |

### 2.2 B 组：编译（我亲自重跑，独立于执行者）

| # | 项 | 结果 | 命令与输出片段 |
| --- | --- | --- | --- |
| B1 | PC 全量 javac | **PASS** | `javac -encoding UTF-8 -nowarn -d %TEMP%\hfx-review-pc <35 个 src .java> <shim>` → `srccount=35 javac_exit=0 classcount=45`；唯一提示是既有 `Main.java` 用 `ResourceBundle.Control`（deprecated，改前就有） |
| B2 | PC 侧测试脚本 | **PASS** | 见 C1（脚本内部先做全量编译） |
| B3 | Android `core/`+`nio/` 脱离 SDK 编译（S1） | **PASS** | `files=30 S1_exit=0 classes=35`（28 core + 2 nio + shim） |
| B4 | Android 平台层语法（S6） | **PASS（仅语法）** | `javac -J-Duser.language=en -J-Duser.country=US -proc:none -Xmaxerrs 100000 -cp <已编译 core> -d %TEMP%\hfx-review-s6c <14 个改动文件>` → `S6_exit=1 (929 error 行)`；错误种类普查：`package ... does not exist`=405、`cannot find symbol`=456；**语法类关键字计数 `expected=0 / illegal start=0 / reached end of file=0 / not a statement=0 / unclosed=0`** → 只有环境类错误（无 `android.jar`、无 `R`），无语法错误。属**环境限制**，不等于"能编译" |
| B5 | AIDL 事务号唯一 | **PASS** | 打印 AIDL：1..13 + `getFileLength=14` + `destroy=16777114`，无重复 |

### 2.3 C 组：执行测试（PC，真实运行）

| # | 项 | 结果 | 命令与输出片段 |
| --- | --- | --- | --- |
| C1 | `run-resume-selftest.ps1` 全绿 | **PASS** | `powershell -ExecutionPolicy Bypass -File HybridFileXfer-PC\test\run-resume-selftest.ps1` → `compiling 35 production files (CoreOnly=False)` / `PASS T1 BlockBitmap` … `PASS T7 JdkResumeStore` / `SELFTEST passed=7 failed=0` / `SELFTEST PASS` / **`=== EXITCODE=0 ===`**（`$LASTEXITCODE` 自测，排除管道假 0） |
| C2 | T1–T7 是否"真跑"而非注释/跳过 | **PASS** | 逐行通读 `ResumeSelfTest.java`（791 行）：自定义 runner 无 skip 机制，7 个 case 全部 `runCase(...)` 注册；`check()` 抛 `AssertionError`，`failed>0` → `System.exit(1)`（若被注释掉则脚本因找不到 case 会打印 passed<7）。断言强度抽样：T2 用 1MB 随机数据 × 32 轮随机 offset/length 与 `java.util.zip.CRC32` 逐一比对；T5/T6 用真实 3.5MB 文件 + `JdkReadFileCall/JdkWriteFileCall` 真实读写并逐字节比对；T7 故意写坏 JSON 后要求"返回空表 + 删文件" |
| C3 | T5/T6 覆盖"跳过集/续传写" | **PASS** | 输出 `PASS T5 ReadFileCall skip set`、`PASS T6 WriteFileCall resume write`；T6 还断言了清单位图恰为 {0,2,3} 且 `completed=false`、块 1 区段仍为 0xFF |
| C4 | T7 清单（含反斜杠/引号/中文/控制字符/坏文件） | **PASS** | 输出 `PASS T7 JdkResumeStore` + `Warning: broken resume manifest removed: ...`（坏文件被删的实测痕迹，符合契约 §3.3） |
| C5 | PC 命令行冒烟（`-v` / `-h` / 非法参数） | **PASS** | `java -cp <classes>;<src> ...Main -v` → `HybirdFileXfer v3.0.1` + **`protocol: 301`**；`-h` → 帮助里出现 `--crc` 行**且** `-h, --help` 行仍在（`help_pages` 被合并成一个物理行依赖 properties 续行，渲染结果正确）；`--bogus` → 未知选项提示 + `exit=1` |

### 2.4 D 组：审查者自建端到端集成测试（覆盖契约"可选 T8"，执行者未做）

**为什么做**：T4 只测编解码、T5/T6 分别测读侧/写侧，二者**从未串在一次真实会话里跑**（执行者 notes §5.1 已如实承认）。
这是本轮最大的未覆盖风险，因此审查者在仓库外自建 loopback 会话（真实 `HFXService.sendFiles/receiveFiles` + 2 条数据通道 + 真实 `JdkReadFileCall/JdkWriteFileCall`）。

```
$ java -cp "%TEMP%\hfx-review-pc;%TEMP%\hfx-itest-classes" ResumeITest
PASS E1 full transfer (dir + files + zero byte), manifest deleted
PASS E2 resume: block 0 skipped (still 0xFF), blocks 1..3 written, bytes match
PASS E3 crc enabled: wire layout matches on both ends, bytes identical
PASS E4 crc mismatch aborts and the corrupt block never reaches the disk
        (type=1 java.io.IOException: CRC32 mismatch: ...\dst\a.bin block=1 expected=0xd424bdc1 actual=0x6d5d707a)
PASS E5 abortResumeFile clears + persists the file row
PASS E6 length mismatch on the target -> no reply -> whole file resent, no zero-filled prefix
ITEST passed=6
ITEST PASS        (itest_exit=0)
```

| 用例 | 覆盖的契约条目 | 断言要点 |
| --- | --- | --- |
| E1 | §2.3 全链路、§3.5 成功即删、目录幂等、零字节 | 2 条数据通道真实并发；选择集含目录 + 3.5MB 文件 + 子目录 0.5MB + 0 字节文件；两侧 `onComplete` 正确（isUpload=true/false），无任何错误回调；目录树逐字节一致；成功后清单文件被删除 |
| E2 | **续传核心**：§2.4 回执→§5.1 跳过集→§5.3 随机定位→§6.4 位图推进 | 预置"block 0 已完成 + 目标已预填 0xFF"，跑真实会话：block 0 区段**仍是 0xFF**（证明该块真的没上线），其余块与源逐字节相同；会话成功后清单被删 |
| E3 | §4.2 CRC 在 wire 上的位置（开） | `setBlockCrcEnabled(true)` 两侧全程无错、字节一致 → 写端/读端 `int crc32` 位置一致（若错位必然 desync） |
| E4 | §4.4 校验失败处理（**独立缺陷注入**） | 用自定义 `ByteChannel` 在写侧翻转数据首字节（CRC 由发送端在翻转前算出）→ 接收端 `CRC32 mismatch` + `ERROR_TYPE_EXCEPTION`、**没有** `onComplete`、且目标文件首块区段仍为 0x00（**坏块未落盘**，因校验在 `putBlock` 之前） |
| E5 | §4.4 `abortResumeFile` + §3.5 "立即落盘" | 先 `onBlockWritten`+`flush` 使行落盘 → `abortResumeFile` → **另建实例**从磁盘读回，行已消失 |
| E6 | §5.2 / R2"目标在断点期间被改" | 清单说 3.5MB、实际目标只有 2MB → 协商阶段长度不符 → 不回执 → 整文件重传 → 最终逐字节正确（不产生"前半截全零"坏文件） |

> 结论：执行者未覆盖的**集成层**经独立验证无缺陷。E4 还顺带证实了"多通道下任一通道块损坏都会整批中止"这一契约期望行为。

### 2.5 E 组：Android 静态审查（无 SDK，逐条读码）

| # | 验收项 | 结果 | 证据 |
| --- | --- | --- | --- |
| E-a | Manifest 权限/服务声明 | **PASS** | 4 个权限齐全；`foregroundServiceType="dataSync"`、`exported="false"`、`stopWithTask="false"`、`tools:targetApi="34"`、**未**指定 `android:process`（主进程） |
| E-b | 前台服务 API 版本分支 | **PASS** | `Build.VERSION.SDK_INT >= Q` → `startForeground(id,n,FOREGROUND_SERVICE_TYPE_DATA_SYNC)`，否则两参；`< O` 跳过建渠道；`PendingIntent` 用 `FLAG_IMMUTABLE`（API23+，minSdk24 安全）；`startForeground` 在 `onCreate()`（5s 窗口内） |
| E-c | WakeLock 成对 | **PASS** | `onCreate→acquireWakeLock()`、`onDestroy→releaseWakeLock()`；`setReferenceCounted(false)`；`release` 前先判 `isHeld()`；`acquire` 幂等（字段非空直接返回）；全部 try/catch 降级 |
| E-d | applicationContext 绑定 | **PASS** | `MainActivity`/`ClientActivity` 均 `getApplicationContext()` + `appContext.bindService(...)`/`Shizuku.bindUserService(IOService.getUserServiceArgs(appContext),...)`；新增 `bound` 标记防重复 unbind；`IOService.getUserServiceArgs` 内部再兜一层 ApplicationContext；`HFXServer/AndroidResumeStore/TransferForegroundService/RootWatchdog` 全部只持 App Context |
| E-e | watchdog 降级 | **PASS（读码）/ 真机未验** | 无 `su`、`uid!=0`、超时、任何异常→静默返回并复位 `requested`；kill 前 `cat /proc/<pid>/cmdline` 校验含脚本名；停服时 `Process.destroy()` + 后台 `kill -9`（不阻塞主线程） |
| E-f | 暂停按钮是否真接到门控 | **PASS** | `TransferDialog.togglePause()` → `gateSupplier.get()` → `TransferActivity.gateSupplier()` → `server.getCurrentGate()` → `HFXService.currentGate`（`sendFiles/receiveFiles` 开头赋值、`finally` 置空）；`gate.pause()/resume()` 即 `TransferGate`；点击事件被覆盖故不关闭对话框；传输结束后 neutral 被禁用 |
| E-g | 强制唤醒三处调用点（R15） | **PASS** | grep `abortTransfer()`：`HFXClient.java:217`（handleShutdown 开头）、`HFXServer.java:191`（closeServerSocket 开头）、`HFXServer.java:301`（disconnect 开头）——**三处全在** |
| E-h | AIDL 变更配套 | **PASS** | `getFileLength=14` + `IOServiceImpl` 实现 + `DroidWriteFileCall.lengthOfFile` + `version(2)`（旧 daemon 会重建）；`IOServiceImpl.createAndOpenWriteableFile` 为 `RandomAccessFile("rw")+setLength` + `MODE_WRITE_ONLY`（**不截断**），`DroidReadFileCall.openFile` 为 `FileInputStream(fd).getChannel()`（支持 `position()`）→ Android 侧续传的两个前提成立（仅读码，未运行） |
| E-i | CRC 开关链路 | **PASS** | `Config("block_crc",false)` → 菜单切换（即时同步到 server）+ `onCreateOptionsMenu` 回填 → `MainActivity.onServiceConnected` 与 `ClientActivity.onServiceConnected` 分别注入；接收端遵从 query 值（`writeFileCall.setCrcEnabled(query.crcEnabled)`，在 ReceiveFileCall 线程启动之前设置，happens-before 成立） |

### 2.6 F 组：边界核对（契约 §10 / §6.5）

| 边界 | 结果 | 依据 |
| --- | --- | --- |
| 断点期间**源**文件被改（R1） | **PASS** | 顶层文件 size/mtime 进 taskKey（T3 断言"size 变 1 → 键变"、"目录 mtime 变 → 键不变"）；树内文件变 → `blockCount/totalSize` 不匹配 → 不可信全传（`ResumeSkipSet.isSendable` 有专门分支，T4 断言 `isSendable(path,0,10)==true`） |
| 断点期间**目标**文件被删/被改（R2） | **PASS** | 代码 `buildResumeReply` 长度校验 + `abortFile`；**E6 实测**整文件重传、无零填充前缀 |
| 部分 block（核心场景） | **PASS** | **E2 实测**（block 0 跳过、其余补齐、字节一致） |
| 多通道并发（R4） | **PASS（PC 侧）/ 真机未验** | E1–E4 全部 2 条数据通道真实并发；位图只由单写线程 `WriteFileCall` 修改、`ResumeSession` 公开方法全 `synchronized`、`abortFile` 由 `ReceiveFileCall` 异常路径调用——代码结构符合 R4；E2 验证了位图内容正确性 |
| 整文件已完成 | **PASS** | T5 case B（不 checkout 缓冲、0 数据块）+ E2（跳过第 0 块后整体补齐）；协商阶段 `isAllCompleted` 时 `touchFile` 修 mtime |
| 零字节文件 | **PASS** | `calcBlockCount(0)=1`；T5 case D（1 个长度 0 的块）；**E1 实测** `sub/empty.bin` 端到端落盘为 0 字节 |
| 目录幂等 | **PASS** | `readToDeque` 目录块永远重发；E2 在 `dst/sub` 已存在的情况下重跑仍成功；`WriteFileCall` 对目录块 `tryMkdirs + setLastModified` |
| CRC 开/关两路（R5） | **PASS** | 关：E1/E2（T1–T7 全程 crc=false）；开：E3；失败路径：E4。关闭时零开销由代码保证（`if (checkCrc)`/`if (readFileCall.isCrcEnabled())` 分支包住读写算） |
| 暂停中"停止/退出"不挂死（R15） | **PASS（代码）/ 真机未验** | E-g 三处调用点齐备；`TransferGate.abort()` 幂等 + `notifyAll()`；`WriteFileCall` 中止分支 `break` 后仍走尾部 `force/close/setLastModified/flush`（不泄漏 fd） |

### 2.7 G 组："未验证"声明诚实性核对（红线）

| 声明 | 核对结果 |
| --- | --- |
| Android 侧只做 S1/S3/S4/S5/S6，未做功能验证 | **诚实**。`resume-notes-android.md` §6 与 `keepalive-notes.md` §4 都逐条列出未验证项（含"AIDL 事务号混用""ConfigDB v1→v2 升级""前台服务/通知""watchdog 真机行为""息振 30min"），且明确写"不得把 javac 语法通过写成编译通过"。本报告与之一致（§2.5 全部标注"读码"或"真机未验"） |
| PC 侧"真实可执行验证" | **诚实且可复现**。7/7 全绿、exit=0、45 个 class、`-v` 打印 `protocol: 301`、坏清单被删除——**五项均由我独立复现** |
| "T8 端到端未做" | **诚实**。执行者明确列为未覆盖项；本报告 §2.4 已由审查者补做并全绿 |
| 有无"改绿/删断言"痕迹 | **无**。测试源码中未见 skip/注释掉断言；`ResumeSelfTest` 的历史失败被如实记录（notes §3.5：T6 首跑是**测试断言写错**，已定位到 `generateTransferPath` 语义并修正，未改产品代码） |
| 执行者 notes 与我的实测冲突项 | 仅 1 处，且非功能问题：`keepalive-notes.md` §3.1 的产物 SHA256 有 3 条已过期（F3） |

---

## 3. 问题清单（分级）

### 3.1 Blocking（P0/P1）——**无**

未发现：协议两端不一致、panic 级编译错误、假通过、改绿、契约常量/位序/CRC 放置/暂停门控/清单 schema 的错误实现。

### 3.2 契约一致性偏差（**非阻断**，建议 lead 裁决：补齐 or 修订契约）

| ID | 级别 | 位置 | 触发条件 | 影响 | 证据 | 最小修复建议 |
| --- | --- | --- | --- | --- | --- | --- |
| **F1** | P2 | `core/HFXService.java:153-157`（`receiveFiles` 协商） | 任意一次传输会话 | 契约 §2.3/§2.5 要求"接收方用收到的 entry 列表重算 taskKey，**与对端值比对**"，实现只算了本地键并直接查清单，**未比对** `query.taskKey` → 该自检（能捕获 UTF 编解码/序列化分叉）缺失；wire 上的 `taskKey` 在接收侧成为死字段 | 读码：`localTaskKey = ResumeTaskKey.compute(query.entries)` 之后没有再使用 `query.taskKey`；T4 只做编解码往返，未断言"键不一致时的行为" | 在 `compute` 之后加 3 行：若 `!localTaskKey.equals(query.taskKey)` 则 `throw new IOException("task key mismatch")`（或打印告警后继续）。**功能影响为零**（两侧同码同字节），故不阻断 |
| **F2** | P3 | `core/ResumeTaskKey.compute` + `HFXService.sendFiles:62-64` | 选择集为空（`entryCount==0`） | 契约 §2.4 规定"entryCount==0 时 taskKey 写 `\"\"`"，实现写的是 32 位 hex 摘要（T3 甚至断言"空集合仍产出 32 位键"） | 读码 + `ResumeSelfTest.t3():278` | 要么在 `writeQuery` 处按契约写空串，要么修订契约文字（推荐后者：空集也必须可区分，写摘要更自洽） |

### 3.3 待验证风险（Android 运行时，本机无法执行；建议列入真机回归清单）

| ID | 级别 | 位置 | 说明与建议 |
| --- | --- | --- | --- |
| **F6** | P2 | `foreground/RootWatchdog.java:176-177` | watchdog 用 `am start-service -n <pkg>/.IOService` 恢复的是**清单里的 Service 进程**；而 root 模式下真正干 IO 的是 Shizuku **user service**（同进程名）。进程被拉起后 `pidof` 会命中，但 Shizuku user service 未必恢复 → 下一次绑定仍可能失败。真机验证点：kill `:IOService` 后 30s 被拉回，且**新传输能成功**（不只看进程存在） |
| **F7** | P2 | `IOService.onStartCommand` 新增 `START_STICKY` | 被 watchdog 以 `start-service` 拉起后，服务处于"已启动"状态，`unbindService()` 不再触发 `onDestroy` → 与契约 §8.1 记录的"解绑即 `destroy()`→`System.exit(0)`"语义不同（`:IOService` 进程会常驻）。方向与保活目标一致，但属行为变更，需真机确认不产生"旧 IIOServiceImpl 未 destroy 就换新实例"的泄漏 |
| **F8** | P2 | `ClientActivity.onDestroy` | 客户端侧"**暂停中**退出 Activity"没有 `abortTransfer()`（契约 §6.3 只列了 3 处，均已实现）。若此时本端是发送方，线程会停在 `awaitUnpaused()` 直到对端关闭（R12 已接受该代价）。建议下一轮把"Activity 销毁/解绑"也纳入强制唤醒路径 |
| **F11** | P3 | `foreground/TransferForegroundService.java:180-195` | `showForegroundNotification` 把所有 `Throwable` 吞掉：若 `startForeground` 本身失败（如 ROM 拦截），服务并未真正前台化，系统仍会在 ~5s 后以 `RemoteServiceException` 杀掉应用——catch 并不能真正"降级"。建议失败时 `stopSelf()` + 日志 |

### 3.4 文档 / 绑定 / 本地化

| ID | 级别 | 位置 | 说明 | 证据 |
| --- | --- | --- | --- | --- |
| **F3** | P3 | `docs/keepalive-notes.md` §3.1 | 声称"终版产物校验摘要"，但 6 条 SHA256 中 **3 条已过期**（这三个文件后来被 task-3 改过）：`MainActivity` 实际 `AACE5084F17C5F7F`（声称 8DE7A2E238D24D66）、`ClientActivity` 实际 `66629886CE57D62C`（声称 2FA26DD168368791）、`IOService` 实际 `AD4087CE397EEA4E`（声称 DC2AC28AC85F436A）。另 3 条（TransferForegroundService / RootWatchdog / AndroidManifest）**完全吻合**。建议 task-2 的 notes 追加一句"该摘要对应 task-3 之前字节"，或重新绑定现值 | `Get-FileHash -Algorithm SHA256` 逐文件比对（见 §4 复现命令） |
| **F4** | P3 | `core/WriteFileCall.java:142-147` | 契约 §6.2 描述"`while (block != null)` 顶部、`takeBlock()` 之前"，实现把门控放在**循环尾部**（`takeBlock()` 之前）。因 `takeBlock()` 在进入循环前先执行一次，二者功能等价（每块边界一次判定、绝不打断块内状态），但文字与实现不一致，建议在契约里改写为"每次 `takeBlock()` 之前" | 读码 |
| **F5** | P3 | `HybridFileXfer-PC/src/messages_*.properties` | `ja/ko/zh_TW` 未补 `--crc` 帮助行（仅 en / zh_CN）；`block_crc_enabled` 五语齐全（故无 `MissingResourceException` 风险，已实测）。另 en/zh_CN 的 `--crc` 行与 `-h` 行被合并成**一个物理行**依赖 properties 续行，渲染后 `-h, --help` 少一个前导空格（纯排版）。建议后续整理为独立物理行 | 5 个语言包 key 普查 + `Main -h` 实跑输出（见 §2.3 C5） |
| **F9** | P3 | `core/HFXService.java:63-71` | `blockCrcEnabled`（volatile）被读两次：一次写 query、一次 `readFileCall.setCrcEnabled`。若用户在两条语句之间切菜单，会出现"wire 上说开、实际不算 CRC"的极窄窗口。建议先读到局部变量再两处复用 | 读码 |
| **F10** | P3 | `JdkResumeStore.flush/purgeExpired` | 每次 flush 重写整个任务 JSON（O(entries)）；`purgeExpired` 每次会话把所有 `*.json` 全量重解析。当前规模可接受，仅记录 | 读码 |
| **F12** | P3 | 契约 §7.5#1 / §1.1 | 契约写"18 老 + 9 新 = 27 个文件"，实际老文件 **19** 个（bean 4 + callback 3 + 顶层 12），总数 **28**（本报告 A1 已按 28 核对通过）。属契约文档计数笔误 | 目录列举（见 §1.2 前的说明） |

### 3.5 测试脆弱性（不影响本轮结论，供下轮参考）

| ID | 说明 |
| --- | --- |
| F13 | 契约 §9.2 T5 要求"全置位 → **只取到 1 个 `END_POINT`**"，测试只断言"0 个数据块"（未断言哨兵个数）。效果等价，但断言弱于文字要求 |
| F14 | `run-resume-selftest.ps1` 把构建产物放到 `%TEMP%`（契约 §9.4 写的是项目内 `build-resume-selftest`）。这是**改进**（工作区保持干净，我已确认 `git status` 无构建产物），且执行者已在 notes §3.1 主动说明——建议修订契约而不是改脚本 |
| F15 | T2 的"1MB 随机数据"用固定种子（`new Random(20261026L)`）→ 可复现，但也意味着每次跑的是同一组 offset/length；覆盖略窄但不影响正确性判断 |

---

## 4. 复现命令（本报告全部证据）

```powershell
# 0) 变更全景
cd D:\2Code\HybridFileXfer
git status --porcelain; git diff --shortstat

# 1) 两端 core/nio 一致性（A1/A8）
$a='...\HybridFileXfer-Android\app\src\main\java\top\weixiansen574\hybridfilexfer\core\'
$b='...\HybridFileXfer-PC\src\top\weixiansen574\hybridfilexfer\core\'
# 双向 MD5 + A/B-ONLY 比对 → 期望 "core files android=28 pc=28 parity_bad=0"

# 2) PC 全量编译（B1）
javac -encoding UTF-8 -nowarn -d %TEMP%\hfx-review-pc <src 下 35 个 .java> <test\shim\...\NotNull.java>
#   期望 exit=0、45 个 class

# 3) PC 自测（C1–C4）
powershell -ExecutionPolicy Bypass -File HybridFileXfer-PC\test\run-resume-selftest.ps1
#   期望 SELFTEST passed=7 failed=0 / SELFTEST PASS / exit 0

# 4) Android core+nio 脱离 SDK 编译（B3）
javac -encoding UTF-8 -nowarn -d %TEMP%\hfx-review-acheck\classes <core 28 + nio 2 + shim>
#   期望 exit=0、35 个 class

# 5) Android 平台层语法（B4，只允许环境类错误）
javac -J-Duser.language=en -J-Duser.country=US -encoding UTF-8 -nowarn -proc:none -Xmaxerrs 100000 `
      -cp %TEMP%\hfx-review-acheck\classes -d %TEMP%\hfx-review-s6c <14 个改动文件>
#   期望：只有 "package ... does not exist" / "cannot find symbol"；无 expected / illegal start / reached end of file

# 6) CLI 冒烟（C5）
java -cp "%TEMP%\hfx-review-pc;HybridFileXfer-PC\src" top.weixiansen574.hybridfilexfer.Main -v   # protocol: 301
java -cp "%TEMP%\hfx-review-pc;HybridFileXfer-PC\src" top.weixiansen574.hybridfilexfer.Main -h   # 含 --crc 行
java -cp "%TEMP%\hfx-review-pc;HybridFileXfer-PC\src" top.weixiansen574.hybridfilexfer.Main --bogus  # exit=1

# 7) 审查者自建端到端集成测试（§2.4，源码在仓库外）
javac -encoding UTF-8 -nowarn -cp %TEMP%\hfx-review-pc -d %TEMP%\hfx-itest-classes %TEMP%\hfx-itest\ResumeITest.java
java -cp "%TEMP%\hfx-review-pc;%TEMP%\hfx-itest-classes" ResumeITest
#   期望 ITEST passed=6 / ITEST PASS / exit 0

# 8) 产物 SHA256 绑定核对（F3）
cd HybridFileXfer-Android\app\src\main
foreach ($f in 'java\...\foreground\TransferForegroundService.java','java\...\foreground\RootWatchdog.java',
               'java\...\MainActivity.java','java\...\ClientActivity.java','java\...\IOService.java','AndroidManifest.xml') {
  (Get-FileHash $f -Algorithm SHA256).Hash.Substring(0,16) + '  ' + $f
}
```

---

## 5. Verdict

**PASS**（可进入集成/交付；Android 运行时仍需真机回归）

放行依据：
1. 契约中所有"可静态判定"的硬指标（§7.5 九条、版本 301、常量 20/21/22、位序、CRC 放置、AIDL 14 + version(2)、暂停门控与三处强制唤醒、清单 schema 与 9 条不变量）**逐条 PASS**，两组 `core/` 字节级一致；
2. PC 侧的执行证据**真实且可复现**（我独立重跑：7/7 全绿、exit=0、45 class），测试源码无跳过、无改绿；
3. 执行者未覆盖的**集成层**由审查者自建 `ResumeITest` 补齐（6/6），首次实测了"协商→回执→跳过→续传写→清单生命周期"与 CRC 失败路径，**未发现缺陷**；
4. Android 侧未验证项**被诚实声明**，且与契约 §9.5 的验证边界完全一致；本报告未把任何"语法通过"当作"功能通过"。

放行时请 lead 一并处理以下裁决项（均非阻断）：
* **F1**（契约要求的 taskKey 对端比对未实现，3 行可补）/ **F2**（空选择集的 taskKey 契约文字与实现不一致）——建议：F1 补齐或修订契约；F2 修订契约文字。
* **F3**（keepalive notes 的 3 条 SHA256 绑定已过期）——建议让 task-2 的 notes 标注"对应 task-3 之前字节"，避免复审时误判产物身份。
* **F6/F7/F8/F11**——纳入真机回归清单（尤其 F6：watchdog 恢复的是进程还是可用的 Shizuku user service，直接影响"需求C 第 4 点"是否真的成立）。
* **F5/F13/F14/F15/F4/F9/F10/F12**——技术债与文档整理，可并入下一轮。

**未纳入本次放行声明的能力**（不得对外宣称已验证）：
Android 真机上的编译/安装/前台服务通知/息屏保活/watchdog 拉起、AIDL 新旧混用、`ConfigDB` v1→v2 升级、Android↔PC 真机跨端 301 互连、Android 侧续传写盘与暂停按钮的真实行为。
