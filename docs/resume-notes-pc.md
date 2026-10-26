# 需求B（PC 侧镜像）实现记录 — 续传协议 / 清单 JSON / 测试

> 任务：task-4（executor）。依据：`docs/design-resume-keepalive.md`（契约）。PC 是纯 Java，**已用 javac 全量编译并跑通最小测试**
> （与 Android 侧"只能静态自检"不同，这一侧是真实可执行验证）。

## 1. 改动文件清单

| 文件 | 动作 | 内容 |
| --- | --- | --- |
| `src/.../core/**`（28 个 .java） | 覆盖 | 由 Android 树逐字节复制（契约 §1.1：两份 core 必须完全相同）。**本任务不含手写改动** |
| `src/.../nio/DataByteChannel.java` | 覆盖 | 同上（含新增 `writeFully(ByteBuffer)` / `writeFully(byte[])`） |
| `src/.../jdkcore/JdkWriteFileCall.java` | 改 | 新增 `lengthOfFile(path)` → `exists() && isFile() ? length() : -1` |
| `src/.../jdkcore/JdkHFXClient.java` | 改 | 新增 `getResumeStore()` → `JdkResumeStore`（懒加载缓存一个实例） |
| `src/.../jdkcore/JdkResumeStore.java` | **新增** | 用户目录 JSON 清单（契约 §3.3）：`-Dhfx.resume.dir` 可覆盖；一任务一文件；临时文件 + `ATOMIC_MOVE`（失败退化 `REPLACE_EXISTING`）；坏文件删除并降级；异常置 broken 后续全 no-op |
| `src/.../Main.java` | 改 | `--crc`/`-crc` 解析（`hfxClient.setBlockCrcEnabled(true)` + 提示）；`-v` 额外打印 `protocol: <VERSION_CODE>` 便于两端排查 |
| `src/messages_en.properties` / `messages_zh_CN.properties` | 改 | 新增 `block_crc_enabled`；`help_pages` 里补 `--crc` 一行 |
| `src/messages_ja.properties` / `messages_ko.properties` / `messages_zh_TW.properties` | 改 | 只新增 `block_crc_enabled`（其余语言回落到各自文件，缺键会抛 MissingResourceException，故必须补齐） |
| `test/top/.../ResumeSelfTest.java` | **新增** | T1–T7 全量自测（纯 ASCII，`\uXXXX` 转义） |
| `test/shim/org/jetbrains/annotations/NotNull.java` | **新增** | 编译期注解 shim（仓库无该 jar） |
| `test/run-resume-selftest.ps1` | **新增** | 一键编译 + 运行；`-CoreOnly` 只编 core+nio（task-3 阶段用过） |

未改：Android 侧任何文件（本轮）、`core/` 的逻辑（由 task-3 定义）。

## 2. 关键实现点

1. **协议完全复用 core**：`RESUME_QUERY/PLAN_END/REPLY`、位图字节序、CRC32 放置、`taskKey` 算法都在共享 `core/`，PC 侧没有第二份实现——只有"清单存储"是平台专属（Android SQLite / PC JSON）。
2. **JdkResumeStore 落盘格式**（与契约 §3.3 逐字节一致，实测产物）：

   ```json
   {"version":1,"taskKey":"0123456789abcdef0123456789abcdef","direction":0,"remotePath":"C:\\pics","entries":[
   {"localPath":"C:\\dst\\a.txt","totalSize":1234,"lastModified":1700000000000,"blockCount":2,"completed":0,"updatedAt":1700000000123,"bitmap":"AQ=="},
   {"localPath":"C:\\dst\\sub\\b\"q\".txt","totalSize":1,"lastModified":1,"blockCount":1,"completed":1,"updatedAt":1700000000999,"bitmap":"AQ=="}]}
   ```

   * 键顺序固定；`bitmap` 为 `java.util.Base64` 基本编码（PC 专属层允许）；
   * 转义只写 `"` → `\"`、`\` → `\\`、`0x00`–`0x1F` → `\u00XX`（小写两位）；解析支持 `\" \\ \/ \uXXXX` 及 `\b\f\n\r\t`；
   * 一任务一文件 `<taskKey>.json`，`<taskKey>.json.tmp` 写完再原子替换；
   * 解析失败/字段缺失 → **删除该文件并按"无清单"处理**（实测见 T7 最后一段）。
3. **内存行缓存**：`JdkResumeStore` 按 taskKey 缓存已解析的 entries，避免每次 `saveEntry` 都整文件重解析；代价是"外部进程手改 json 不会被本进程感知"（单进程使用，可接受）。
4. **`--crc`**：只影响本端"作为发送方"时是否每块带 CRC32；接收方无条件遵从对方设置。
5. **`-v`**：打印 `HybridFileXfer vX` + `protocol: 301`，用于快速判定"连接被拒是不是版本不一致"。

## 3. 测试（命令 + 真实输出）

### 3.1 一键脚本

```powershell
powershell -ExecutionPolicy Bypass -File D:\2Code\HybridFileXfer\HybridFileXfer-PC\test\run-resume-selftest.ps1
```

输出（真实）：

```
compiling 35 production files (CoreOnly=False)
PASS T1 BlockBitmap
PASS T2 Crc32
PASS T3 ResumeTaskKey
PASS T4 resume codec
PASS T5 ReadFileCall skip set
PASS T6 WriteFileCall resume write
PASS T7 JdkResumeStore

SELFTEST passed=7 failed=0
SELFTEST PASS
```

脚本退出码 **0**（另用 `Start-Process -Wait` 复核 `exit=0`，排除管道截断造成的假 1）。

> 与契约 §9.4 的两处**主动偏离**（都是为了让脚本在 PowerShell 5.1 上真的能跑、且不污染工作区）：
> 1. 构建产物放到 `%TEMP%\hfx-resume-selftest`（契约写的是项目内 `build-resume-selftest`）；
> 2. 脚本内容**纯 ASCII + CRLF**。踩坑记录：最初的版本里有一行中文注释且是 LF 换行，PowerShell 5.1 按 ANSI 读取后解析错乱，
>    表现为 `$out` 变 null、`Remove-Item` 报"Cannot bind argument to parameter 'Path' because it is null"（报错行号还和实际文件错位一行）。
>    改成 ASCII-only + CRLF 后一次通过。

### 3.2 全量编译（等价手工命令）

```powershell
$out="$env:TEMP\hfx_pc_classes"
javac -encoding UTF-8 -nowarn -d $out (Get-ChildItem HybridFileXfer-PC\src -Recurse -Filter *.java).FullName `
      HybridFileXfer-PC\test\shim\org\jetbrains\annotations\NotNull.java
# exit=0，45 个 class（唯一提示是 Main.java 使用了既有的 deprecated API：ResourceBundle.Control，改前就存在）
```

### 3.3 命令行冒烟

| 命令 | 结果 |
| --- | --- |
| `java -cp <classes>;<src> ...Main -v` | exit=0，输出 `HybirdFileXfer v3.0.1` + `protocol: 301` |
| `java ... Main --crc` | exit=0，进入"未指定连接方式"分支（说明 `--crc` 被正确识别，不再当作非法参数） |
| `java ... Main --nope` | exit=1，输出"未知选项：--nope"（非法参数仍被拒绝） |

### 3.4 用例覆盖（逐条对应契约 §9.2）

| 用例 | 覆盖点 | 结果 |
| --- | --- | --- |
| T1 | `byteLength(1/8/9/0)`、LSB 位序、`fromBytes` 长度校验抛异常、padding 位归一化且不影响 `isAllCompleted`、越界忽略 | PASS |
| T2 | `"123456789"` → `0xCBF43926`、direct buffer、1MB 随机数据 × 32 轮随机 offset/length 与 `java.util.zip.CRC32` 逐一相等、position/limit 不变、空块=0 | PASS |
| T3 | 顺序无关、顶层文件 size 变则键变、目录 mtime 不影响、32 位小写 hex、空集合 ≠ 单空目录 | PASS |
| T4 | loopback SocketChannel 上 `RESUME_QUERY` 3 条目（含中文+反斜杠）逐字段回读 + 流对齐哨兵；`RESUME_REPLY` 2 条（blockCount 9/17，位图非 8 倍数）；跳集语义 6 条断言；错误消息号/坏 bitmap 长度/负 entryCount 均抛 IOException | PASS |
| T5 | 3.5MB 源文件：`{block1}` → 块序号恰为 `[0,2,3]`、长度 `[1M,1M,0.5M]`、块 2 内容与源 offset 2MB 逐字节相同；全完成 → 0 个数据块；`EMPTY` → 4 块全对；0 字节 → 1 个长度 0 的块 | PASS |
| T6 | 目标预填 `0xFF` 且 `setLength(3.5MB)`，只投递 `[0,2,3]` → `[0,1M)`、`[2M,3M)`、`[3M,3.5M)` 与源一致、`[1M,2M)` 仍为 `0xFF`；`ResumeSession` 位图恰为 {0,2,3} 且 `completed=false`；再跑全 4 块 → 整文件与源逐字节相同 | PASS |
| T7 | `-Dhfx.resume.dir` 覆盖生效；含 `\`、`"`、中文、`\u0001` 的路径全字段往返；`removeEntry`/`removeTask`（并删文件）；`updatedAt=0` 被 `purgeExpired(7d)` 清掉；**故意写坏 json → 返回空表、不抛异常、坏文件被删除** | PASS |

### 3.5 测试期间修掉的问题（如实记录）

1. `JdkResumeStore` 的 javadoc 里写了 `\u0000`/`\u00XX` 字面量 → **javac 把注释里的 `\u` 也当 unicode 转义**，报 `illegal unicode escape`。改为 `U+0000..U+001F` 表述，重编通过。（教训：Java 源码注释里不能出现裸 `\uXXXX`。）
2. T6 首跑失败：**测试断言写错**——把写入目标当成 `<tmp>/dst.bin`，实际写入路径是发送方生成的 destPath `<tmp>/dst/src.bin`（`generateTransferPath` 决定）。实现无误，已改为按 destPath 构造并预填目标文件。调试过程留下了确定性结论：`WriteFileCall` 的 `data.flip()` + `cursor` 推进 + `channel.position()` 逻辑在"跳块补写"下工作正常。

## 4. 与 Android 侧的一致性核对（契约 §7.5）

| # | 检查项 | 结果 |
| --- | --- | --- |
| 1 | 两份 `core/` 28 个文件 MD5 相同 | PASS（parity bad=0） |
| 2 | `VERSION_CODE == 301` 两处 | PASS |
| 3 | `RESUME_QUERY/PLAN_END/REPLY = 20/21/22` | PASS |
| 4 | 消息读写只在 `ResumeProtocol` | PASS |
| 5 | `core/` 无平台专属 API（无 `import android.`、无 `java.util.Base64`） | PASS（`java.util.Base64` 只在 `jdkcore/JdkResumeStore`） |
| 6 | 位序只在 `BlockBitmap` | PASS |
| 7 | 默认值一致（crc 默认关、`EMPTY` 全发） | PASS |
| 8 | `DataByteChannel` MD5 相同 | PASS |
| 9 | 清单字段映射：SQLite 列（snake_case）↔ JSON 键（camelCase）↔ `ResumeEntry` 字段 | PASS（`task_key/taskKey/taskKey`、`total_size/totalSize/totalSize`、`last_modified/lastModified/lastModified`、`block_count/blockCount/blockCount`、`bitmap/bitmap/bitmap`、`completed/completed/completed`、`updated_at/updatedAt/updatedAt`、`direction/direction/direction`、`remote_path/remotePath/remotePath`、`local_path/localPath/localPath`） |

## 5. 未验证 / 未覆盖

1. **真实端到端**（契约 §9.2 T8 可选、未做）：环回 socket 上跑真实 `SendFileCall`+`ReceiveFileCall` 的完整会话（含 CRC 开关、断线续传第二轮只传缺失块）。T4/T5/T6 分别覆盖了"编解码 / 读侧跳块 / 写侧补写"，但**没有**把两者串在一次真实传输里跑。
2. **PC ↔ Android 真机联调**：Android 侧无 SDK 不可运行，跨端 301 协议实际互连未验证（需用户真机 + PC jar）。
3. `JdkResumeStore` 的多进程并发安全（同机两个客户端实例同时写同一 taskKey 的清单）未处理也未验证 —— 当前按单进程使用设计。
4. `--crc` 开启后的真实传输性能/正确性未跑（只有 CRC 算法本身的等价性测试）。
5. 老版本（300）PC jar 连新 Android（301）会被版本校验拒绝——这是预期行为，但需要给用户写升级提示（交付说明里已注明）。
