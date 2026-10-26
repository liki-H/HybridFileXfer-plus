# 需求C（root 保活）实现记录 — 前台服务 / WakeLock / 服务解耦 / root watchdog

> 任务：task-2（executor）。仅改 Android 侧，未触碰 `core/`、`droidcore/` 的传输逻辑，未改传输协议。
> 环境限制：**本机无 Android SDK**（无 `local.properties`、无 `ANDROID_HOME`、全盘找不到 `android.jar`），
> 因此**没有也无法运行 gradle / 任何 Android 构建**。Android 侧改动全部只有「源码 + 静态自检」，
> 下面第 4 节逐条列出**未编译验证**的项，需真机回归。

## 1. 改动文件清单

| 类型 | 文件 | 说明 |
| --- | --- | --- |
| 新增 | `HybridFileXfer-Android/app/src/main/java/top/weixiansen574/hybridfilexfer/foreground/TransferForegroundService.java` | 前台保活服务（主进程，dataSync 类型，常驻通知 + PARTIAL_WAKE_LOCK + root watchdog 启停） |
| 新增 | `HybridFileXfer-Android/app/src/main/java/top/weixiansen574/hybridfilexfer/foreground/RootWatchdog.java` | root 守护脚本（`sh` 轮询 `pidof <pkg>:IOService`，缺失则 `am start-service`） |
| 修改 | `HybridFileXfer-Android/app/src/main/AndroidManifest.xml` | 新增 4 个权限；声明 `.foreground.TransferForegroundService` |
| 修改 | `HybridFileXfer-Android/app/src/main/java/top/weixiansen574/hybridfilexfer/MainActivity.java` | applicationContext 绑定/解绑；服务端启动时起前台服务、停止时停；API33+ 请求 POST_NOTIFICATIONS；onDestroy 收尾 |
| 修改 | `HybridFileXfer-Android/app/src/main/java/top/weixiansen574/hybridfilexfer/ClientActivity.java` | 同上（客户端侧）；onDestroy 必须显式解绑 |
| 修改 | `HybridFileXfer-Android/app/src/main/java/top/weixiansen574/hybridfilexfer/IOService.java` | `onDestroy` 空指针加固（watchdog 以 `am start-service` 拉起时从未绑定 → `service == null`）；补 `onStartCommand` 返回 `START_STICKY`；`getUserServiceArgs` 内部改用 ApplicationContext |
| 修改 | `HybridFileXfer-Android/app/src/main/res/values/strings.xml` | 新增 5 条英文文案 |
| 修改 | `HybridFileXfer-Android/app/src/main/res/values-zh/strings.xml` | 新增 5 条中文文案（同 key） |
| 新增 | `docs/keepalive-notes.md` | 本文件 |

未改动：`core/`、`droidcore/`、`tasks/`、`Config*`、`TransferActivity`、`TransferDialog`、PC 侧任何文件。

## 2. 关键实现点

### 2.1 前台服务 `TransferForegroundService`

- **进程**：Manifest 中不写 `android:process`，即运行在主进程，与被保活对象（`HFXServer` + socket + 传输线程）同进程。
- **声明**：`android:exported="false"`、`android:foregroundServiceType="dataSync"`、`android:stopWithTask="false"`、`tools:targetApi="34"`。
- **通知渠道**：`CHANNEL_ID = "transfer_channel"`，`IMPORTANCE_LOW`，`setShowBadge(false)`；渠道仅在 API 26+ 创建，重复调用幂等（先 `getNotificationChannel`）。
- **startForeground**：
  - API 29+ → `startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)`；
  - API 24–28 → 两参 `startForeground(1, notification)`。
  - 调用点放在 `onCreate()`（而非 `onStartCommand()`），保证 `startForegroundService()` 的 5 秒窗口内一定执行。
- **静态入口**：`start(Context)` / `start(Context, boolean rootMode)` / `stop(Context)`；内部一律 `getApplicationContext()`，
  走 `ContextCompat.startForegroundService()` / `stopService()`；异常一律 catch 后降级（API 31+ 后台启动限制、厂商拦截等不会崩溃、不影响传输）。
- **返回 START_STICKY**：被系统杀掉后有机会重建；重建时 intent 可能为 null，用静态 `lastRootMode` 兜底决定是否重启 watchdog。
- **不可绑定**：`onBind()` 返回 null。

### 2.2 PARTIAL_WAKE_LOCK（严格成对）

- `onCreate()` → `acquireWakeLock()`；`onDestroy()` → `releaseWakeLock()`（`wakeLock` 字段先置空再 release，`isHeld()` 判断后再 release，`setReferenceCounted(false)` 避免计数错配）。
- 幂等：`acquireWakeLock()` 若 `wakeLock != null` 直接返回；`releaseWakeLock()` 对 null 安全。
- 所有 PowerManager 调用包 try/catch，异常时降级（不持锁但仍运行）。

### 2.3 Activity 绑定解耦（applicationContext）

| 位置 | 改动前 | 改动后 |
| --- | --- | --- |
| `MainActivity.bindAndStartService()` | `bindService(intent, this, BIND_AUTO_CREATE)`（Activity Context） | 先 `TransferForegroundService.start(appContext, isRootMode)`，再 `appContext.bindService(...)`；Shizuku 分支 `Shizuku.bindUserService(IOService.getUserServiceArgs(appContext), this)` |
| `MainActivity.unbindService()` | `unbindService(this)` | 先 `TransferForegroundService.stop(appContext)`，再 `appContext.unbindService(this)`（Shizuku 分支 `Shizuku.unbindUserService(getUserServiceArgs(appContext), this, true)`） |
| `ClientActivity` | 同上 | 同上（`rootMode = ioMode == 1`） |

- 新增 `boolean bound` 标记：`bindService` 用返回值赋值、Shizuku 分支赋 true；`unbindService()` 开头判断 `!bound` 直接返回，
  避免 `IllegalArgumentException: Service not registered`（改动前的 `unbindService()` 在重复调用时本来就有这个风险）。
- **必须显式解绑**：用 ApplicationContext 绑定后，Activity 销毁**不会**自动解绑，所以两个 Activity 都在 `onDestroy()` 里显式解绑，
  否则会通过 `ServiceConnection` 泄漏 Activity。
- 服务启停配对：
  - 起：`MainActivity.startServer()` 通过全部校验后（`bindAndStartService()` 内）、`ClientActivity.onCreate()` 绑定前；
  - 停：`MainActivity.changeToStartState()` / `disconnect()` 成功回调 / `onDestroy()`，`ClientActivity.onDestroy()` —— 都经由 `unbindService()`，
    另在 `onDestroy()` 再调一次 `TransferForegroundService.stop()` 兜底（`stopService` 幂等）。
- `rootMode` 取值：模式 0（普通）→ false；模式 1（Shizuku 由 root 启动，Uid 0）→ true；模式 2（ADB）→ false。
  只有 root 模式才拉起 watchdog，避免 ADB 模式下误触发 `su` 授权弹窗。

### 2.4 root watchdog `RootWatchdog`

- 入口 `startIfRoot(Context)`（后台线程执行，不阻塞主线程）/ `stop()`（主线程安全，kill 动作转后台线程）。
- root 检测：执行 `su -c id`，输出包含 `uid=0` 视为有 root；超时 5s（用 `available()` 轮询 + `exitValue()` 判断退出，**不使用 `Process.waitFor(long,TimeUnit)` / `isAlive()`**，这两个在 API 26 才有，minSdk 是 24）。
- 脚本写 `filesDir/hfx_root_watchdog.sh`（UTF-8 无 BOM），内容要点：

  ```sh
  #!/system/bin/sh
  PKG='<应用包名>'
  SERVICE_CLASS='<应用包名>.IOService'
  PROC='<应用包名>:IOService'
  PID_FILE='<filesDir>/hfx_root_watchdog.pid'
  echo $$ > "$PID_FILE"
  while true; do
      if ! pidof "$PROC" >/dev/null 2>&1; then
          am start-service -n "$PKG/$SERVICE_CLASS" >/dev/null 2>&1
          am start-service -n "$PKG/.IOService" >/dev/null 2>&1
      fi
      sleep 30
  done
  ```

- 启动：`Runtime.getRuntime().exec(new String[]{"su", "-c", "sh " + scriptPath})`，关闭 stdin 防止占用。
- 停止：`Process.destroy()` + 读 pid 文件后 `su -c "kill -9 <pid>"`；**kill 前先 `cat /proc/<pid>/cmdline` 校验确实指向本脚本**，避免 pid 复用误杀无关 root 进程。
- 防重复：重启动前先按同样方式 kill 上一轮遗留脚本（cmdline 必须含 `hfx_root_watchdog.sh`）。
- 降级：无 `su`（未 root）、用户拒绝授权、任何异常 → 静默返回；不弹通知、不抛异常、不影响传输；失败时复位 `requested` 允许下次再试。
- 与 `IOService` 的配合：`am start-service` 会拉起一个「从未被绑定」的 IOService 实例，原 `IOService.onDestroy()` 里 `service.destroy()` 会 NPE，
  已加空判断；并补 `onStartCommand()` 返回 `START_STICKY`。

### 2.5 Manifest

新增权限：`WAKE_LOCK`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`（API 34 必需）、`POST_NOTIFICATIONS`（API 33+ 运行时权限）。
服务声明：

```xml
<service
    android:name=".foreground.TransferForegroundService"
    android:enabled="true"
    android:exported="false"
    android:foregroundServiceType="dataSync"
    android:stopWithTask="false"
    tools:targetApi="34" />
```

### 2.6 通知权限

`MainActivity.onCreate()` 末尾 `requestNotificationPermissionIfNeeded()`：仅 API 33+（`Build.VERSION_CODES.TIRAMISU`）且未授权时
`ActivityCompat.requestPermissions(this, {Manifest.permission.POST_NOTIFICATIONS}, 3)`。
拒绝时不影响传输，仅常驻通知不显示（前台服务本身仍合规启动）。

## 3. 已做的静态自检（可复现）

| 检查 | 命令/方式 | 结果 |
| --- | --- | --- |
| Manifest / strings XML 良构 | `[System.Xml.XmlDocument]::Load()` 解析三个 XML | 3/3 OK |
| 新增字符串 key 两侧一致 | grep `keepalive_` in `values/strings.xml` 与 `values-zh/strings.xml` | 各 5 条，key 完全一致 |
| 中文编码未损坏 | 以 UTF-8 读取 `values-zh/strings.xml` 回显新增行 | 中文正常（未使用 Get-Content/Set-Content 写文件） |
| Java 语法可解析 | `javac -J-Duser.language=en -proc:none -nowarn -Xmaxerrs 5000 -d %TEMP%\out <5 个改动文件>` | exit=1，579 条错误全部为 `package android.* / androidx.* / R does not exist`、`cannot find symbol`、由前者派生的 `method does not override...`；**无一条语法类错误**（无 `expected` / `illegal start` / `reached end of file` 等） |
| 服务声明/权限落地 | grep Manifest | `foregroundServiceType="dataSync"`、4 个新权限均在（见 2.5） |

> `javac` 只用于**语法解析**：缺 android.jar，符号解析必然报错，因此「通过」只代表语法与括号/结构无误，不代表可编译。

### 3.1 终版产物校验摘要（2026-10-26，改动全部落地后重跑）

- `javac` 语法检查（5 个改动 Java 文件，终版字节）：`exit=1`，共 579 条错误，
  其中 280 `cannot find symbol` / 113 `package R does not exist` / 55 `method does not override…`（均由缺失父类型派生）/
  131 条 `package android.*、androidx.* does not exist`；
  **语法类错误 = 0**（过滤掉上述三类后无任何残留错误行）。
- SHA256（前 16 位，便于审查绑定版本）：
  - `TransferForegroundService.java` `87618E1AACC43620`
  - `RootWatchdog.java` `8F2668A710F97C05`
  - `MainActivity.java` `8DE7A2E238D24D66`
  - `ClientActivity.java` `2FA26DD168368791`
  - `IOService.java` `DC2AC28AC85F436A`
  - `AndroidManifest.xml` `547783C99881F679`

> 注（审查 F3）：上述 `MainActivity.java` / `ClientActivity.java` / `IOService.java` 三条是 **task-3 之前** 的字节；
> task-3（续传）后续又改过这三个文件，当前实际 SHA256 前 16 位分别为
> `AACE5084F17C5F7F` / `66629886CE57D62C` / `AD4087CE397EEA4E`。其余三条与当前字节吻合。


## 4. 未验证项（无 SDK，必须真机回归）

1. **编译**：所有 5 个 Java 文件与 Manifest 都未经 aapt/javac(android.jar) 编译；`R.string.keepalive_*`、`R.drawable.baseline_speed_24` 引用正确性靠人工核对（资源确实存在）。
2. **前台服务**：
   - 通知是否正常显示（渠道 `transfer_channel`、小图标 `baseline_speed_24` 为矢量图，个别 ROM 通知小图标可能不渲染但不崩溃）；
   - `startForeground(1, n, FOREGROUND_SERVICE_TYPE_DATA_SYNC)` 在 Android 14 上是否需要额外声明已核对（Manifest 已声明 `FOREGROUND_SERVICE_DATA_SYNC` 权限 + `foregroundServiceType`）；
   - Android 12+ 若从后台启动会抛 `ForegroundServiceStartNotAllowedException`（当前只在 Activity 前台路径启动，已 try/catch 兜底）。
3. **WakeLock**：息屏长时间传输是否真的不再被休眠打断（需真机息屏 30min+ 验证）；`dumpsys power | grep HybridFileXfer` 应有持锁记录。
4. **绑定解耦**：`applicationContext.bindService` 与 `Shizuku.bindUserService` 混用是否正常；`bound` 标记在 Shizuku 分支是「假定绑定成功」（Shizuku 的绑定失败走回调，不像 `bindService` 有返回值），若 Shizuku 授权失败后走 `unbindService()` 会调用 `Shizuku.unbindUserService`，理论上安全但需真机确认不抛异常。
5. **root watchdog**：
   - 真机（Magisk/KernelSU）`su -c id` 是否返回 `uid=0`、`su -c "sh <私有目录脚本>"` 是否能读应用私有目录文件；
   - `pidof <pkg>:IOService` 在目标 ROM（toybox pidof）是否能匹配带冒号的进程名；
   - `am start-service -n <pkg>/.IOService` 是否被 ROM 的 exported/后台限制拦截（root uid 通常可绕过，**未验证**）；
   - 杀掉 `:IOService` 后 30s 内是否被拉回；
   - 未 root / 拒绝授权场景是否完全静默（不弹 Magisk 授权框之外的东西、不崩溃）。
6. **关屏后 Activity 被系统回收**：本次未处理「Activity 被销毁后继续传输」——见下节限制说明。
7. 未知语言（ja/ko/zh-rTW）新增文案回落英文，未补译（按任务要求只要求 strings.xml 中文）。

## 5. 已知限制与决策点（需 lead/用户确认）

1. **服务器生命周期仍与 MainActivity 存活绑定**：为了让「Activity 销毁 → 传输停止」的既有语义不变、且不通过 `ServiceConnection` 泄漏 Activity，
   两个 Activity 都在 `onDestroy()` 显式 `unbindService()` + `stopService()`。
   因此本轮的「解耦」是**Context 级解耦**（绑定用 ApplicationContext，不再随 Activity 生命周期被动释放、也不再由 Activity Context 携带生命周期语义），
   而**不是**「服务器脱离 Activity 独立常驻」。要真正做到后者，需要引入一个 Application 作用域的单例会话持有者
   （持有 `IIOService` + `HFXServer` + 监听者列表，Activity 只做观察者注册/注销），属于下一步重构，且必须与 task-3 的续传清单改造一起设计，
   否则会出现「新的 MainActivity 实例与旧服务器状态不一致」的问题。**建议作为独立后续任务。**
2. **`MainActivity.onDestroy()` 的错误处理变化**：`BackstageTask.BaseEventHandler.onError` 的默认实现是 `throw new RuntimeException(th)`，
   退出流程里抛出会让 App 在销毁时崩溃，因此新加的 handler 覆写 `onError` 为「解绑 + printStackTrace」。属行为微调，已在代码注释中说明。
3. **`IOService.onDestroy()` 保留 `IOServiceImpl.destroy()`（内部 `System.exit(0)`）**：即解绑会杀掉 `:IOService` 进程，这是原有语义，未改。
4. `/data/data/<pkg>/files` 下的脚本对 root 可读；若某些加固/多用户环境读不到，watchdog 会静默失效（降级，不影响传输）。
