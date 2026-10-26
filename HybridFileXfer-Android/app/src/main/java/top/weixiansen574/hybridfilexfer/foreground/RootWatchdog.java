package top.weixiansen574.hybridfilexfer.foreground;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * root 模式下的 IO 服务守护（watchdog）。
 *
 * <p>实现方式（需求C 第 4 点）：把一段 sh 脚本写到应用私有目录，用
 * {@code su -c "sh <脚本路径>"} 在后台常驻运行；脚本每 30 秒检查
 * {@code <包名>:IOService} 进程是否存在，不存在就用
 * {@code am start-service} 把它拉起来。
 *
 * <p>降级策略：拿不到 root（没有 su / 用户拒绝授权 / 执行异常）时**静默返回**，
 * 不抛异常、不弹通知、不影响正常传输功能。
 *
 * <p>本机无 Android SDK，无法编译验证；真机行为（pidof / am start-service 是否被
 * 厂商 ROM 限制）需真机测试。
 */
public final class RootWatchdog {

    /** 脚本文件名（写在 filesDir 下） */
    private static final String SCRIPT_FILE_NAME = "hfx_root_watchdog.sh";
    /** 脚本自身 pid 文件名（写在 filesDir 下） */
    private static final String PID_FILE_NAME = "hfx_root_watchdog.pid";
    /** 轮询间隔（秒） */
    private static final int INTERVAL_SECONDS = 30;
    /** IOService 的进程名后缀，与 IOService.getUserServiceArgs() 中的 processNameSuffix 保持一致 */
    private static final String IO_SERVICE_PROCESS_SUFFIX = "IOService";
    /** IOService 的类名后缀 */
    private static final String IO_SERVICE_CLASS_SUFFIX = ".IOService";
    /** root 检测超时（毫秒） */
    private static final long ROOT_CHECK_TIMEOUT_MS = 5000L;

    private static final Object LOCK = new Object();

    private static Context appContext;
    private static Process watchdogProcess;
    /** 是否已请求过，避免重复拉起脚本 */
    private static boolean requested = false;

    private RootWatchdog() {
    }

    /**
     * 若可用 root 则启动守护脚本；否则静默降级。
     *
     * <p>root 检测与脚本启动都在后台线程执行，不阻塞调用方（主线程）。
     */
    public static void startIfRoot(Context context) {
        if (context == null) {
            return;
        }
        Context applicationContext = context.getApplicationContext();
        if (applicationContext == null) {
            applicationContext = context;
        }
        synchronized (LOCK) {
            if (requested) {
                return;
            }
            requested = true;
            appContext = applicationContext;
        }
        final Context finalAppContext = applicationContext;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!isRootAvailable()) {
                        // 无 root：静默降级，允许下次再尝试
                        resetRequested();
                        return;
                    }
                    launch(finalAppContext);
                } catch (Throwable th) {
                    // 任何异常都只降级，不向上抛
                    th.printStackTrace();
                    resetRequested();
                }
            }
        }, "HFX-RootWatchdog");
        thread.setDaemon(true);
        thread.start();
    }

    /** 停止守护脚本（kill 脚本进程并清理 pid 文件）。可在主线程调用。 */
    public static void stop() {
        Context context;
        Process process;
        synchronized (LOCK) {
            requested = false;
            context = appContext;
            process = watchdogProcess;
            watchdogProcess = null;
        }
        if (process != null) {
            try {
                process.destroy();
            } catch (Throwable th) {
                th.printStackTrace();
            }
            closeQuietly(process);
        }
        if (context == null) {
            return;
        }
        final File filesDir = context.getFilesDir();
        if (filesDir == null) {
            return;
        }
        final File pidFile = new File(filesDir, PID_FILE_NAME);
        // kill 需要再次走 su，放到后台线程，避免阻塞主线程
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                killByPidFile(pidFile);
                deleteQuietly(pidFile);
            }
        }, "HFX-RootWatchdog-stop");
        thread.setDaemon(true);
        thread.start();
    }

    private static void resetRequested() {
        synchronized (LOCK) {
            requested = false;
        }
    }

    private static void launch(Context context) throws IOException {
        File filesDir = context.getFilesDir();
        if (filesDir == null) {
            resetRequested();
            return;
        }
        File scriptFile = new File(filesDir, SCRIPT_FILE_NAME);
        File pidFile = new File(filesDir, PID_FILE_NAME);
        writeScript(context, scriptFile, pidFile);
        // 先清掉上一次 App 运行遗留的守护脚本，避免同进程里跑两份
        killStaleWatchdog(pidFile);
        deleteQuietly(pidFile);

        String command = "sh " + scriptFile.getAbsolutePath();
        Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
        // 脚本不需要 stdin；关闭避免占用
        try {
            process.getOutputStream().close();
        } catch (Throwable ignored) {
            // 忽略
        }
        synchronized (LOCK) {
            watchdogProcess = process;
        }
    }

    private static void writeScript(Context context, File scriptFile, File pidFile) throws IOException {
        String packageName = context.getPackageName();
        String serviceClassName = packageName + IO_SERVICE_CLASS_SUFFIX;
        String serviceProcessName = packageName + ":" + IO_SERVICE_PROCESS_SUFFIX;
        StringBuilder builder = new StringBuilder();
        builder.append("#!/system/bin/sh\n");
        builder.append("# 由 HybridFileXfer 自动生成，请勿手动修改\n");
        builder.append("PKG='").append(packageName).append("'\n");
        builder.append("SERVICE_CLASS='").append(serviceClassName).append("'\n");
        builder.append("PROC='").append(serviceProcessName).append("'\n");
        builder.append("PID_FILE='").append(pidFile.getAbsolutePath()).append("'\n");
        builder.append("echo $$ > \"$PID_FILE\"\n");
        builder.append("while true; do\n");
        builder.append("    if ! pidof \"$PROC\" >/dev/null 2>&1; then\n");
        builder.append("        am start-service -n \"$PKG/$SERVICE_CLASS\" >/dev/null 2>&1\n");
        builder.append("        am start-service -n \"$PKG/.IOService\" >/dev/null 2>&1\n");
        builder.append("    fi\n");
        builder.append("    sleep ").append(INTERVAL_SECONDS).append("\n");
        builder.append("done\n");

        FileOutputStream outputStream = null;
        try {
            outputStream = new FileOutputStream(scriptFile, false);
            // 不带 BOM 的 UTF-8：脚本由 /system/bin/sh 读取
            outputStream.write(builder.toString().getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException ignored) {
                    // 忽略
                }
            }
        }
    }

    /** 若上一次运行留下了守护脚本（cmdline 里确认是本脚本），先 kill 掉。 */
    private static void killStaleWatchdog(File pidFile) {
        int pid = readPid(pidFile);
        if (pid <= 0) {
            return;
        }
        String cmdline = readProcCmdline(pid);
        if (cmdline != null && cmdline.contains(SCRIPT_FILE_NAME)) {
            execSuQuietly("kill -9 " + pid);
        }
    }

    private static void killByPidFile(File pidFile) {
        int pid = readPid(pidFile);
        if (pid <= 0) {
            return;
        }
        String cmdline = readProcCmdline(pid);
        // 只有确认该 pid 仍是我们的脚本才 kill，避免 pid 复用误杀
        if (cmdline != null && cmdline.contains(SCRIPT_FILE_NAME)) {
            execSuQuietly("kill -9 " + pid);
        }
    }

    private static String readProcCmdline(int pid) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat /proc/" + pid + "/cmdline"});
            String output = readAvailable(process, 2000L);
            if (output == null || output.isEmpty()) {
                return null;
            }
            return output;
        } catch (Throwable th) {
            return null;
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                    // 忽略
                }
                closeQuietly(process);
            }
        }
    }

    private static void execSuQuietly(String command) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            // 等待短暂时间，避免进程句柄立即丢弃
            waitForExit(process, 2000L);
        } catch (Throwable th) {
            // 静默降级
        } finally {
            if (process != null) {
                closeQuietly(process);
            }
        }
    }

    /** 检测 root：执行 {@code su -c id}，输出里包含 uid=0 视为有 root。 */
    private static boolean isRootAvailable() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            String output = readAvailable(process, ROOT_CHECK_TIMEOUT_MS);
            if (output == null) {
                return false;
            }
            return output.contains("uid=0");
        } catch (Throwable th) {
            // 没有 su（未 root 设备）：静默降级
            return false;
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                    // 忽略
                }
                closeQuietly(process);
            }
        }
    }

    /**
     * 在超时内读取子进程 stdout（su 可能卡在授权弹窗上，不能无限阻塞）。
     *
     * @return 读到的内容；超时未结束返回 null
     */
    private static String readAvailable(Process process, long timeoutMs) {
        if (process == null) {
            return null;
        }
        InputStream inputStream = process.getInputStream();
        StringBuilder builder = new StringBuilder();
        byte[] buffer = new byte[512];
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            while (System.currentTimeMillis() < deadline) {
                int available = inputStream.available();
                if (available > 0) {
                    int read = inputStream.read(buffer, 0, Math.min(available, buffer.length));
                    if (read > 0) {
                        builder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                    continue;
                }
                if (hasExited(process)) {
                    // 进程已结束，把剩余内容读干净
                    while (inputStream.available() > 0) {
                        int read = inputStream.read(buffer, 0,
                                Math.min(inputStream.available(), buffer.length));
                        if (read <= 0) {
                            break;
                        }
                        builder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                    return builder.toString();
                }
                Thread.sleep(50L);
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return builder.length() > 0 ? builder.toString() : null;
        } catch (IOException e) {
            return builder.length() > 0 ? builder.toString() : null;
        }
    }

    private static boolean waitForExit(Process process, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (hasExited(process)) {
                return true;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** Process.isAlive() 需要 API 26，minSdk 24 用 exitValue() 判断。 */
    private static boolean hasExited(Process process) {
        try {
            process.exitValue();
            return true;
        } catch (IllegalThreadStateException e) {
            return false;
        } catch (Throwable th) {
            return false;
        }
    }

    private static int readPid(File pidFile) {
        if (pidFile == null || !pidFile.isFile()) {
            return -1;
        }
        InputStream inputStream = null;
        try {
            inputStream = new java.io.FileInputStream(pidFile);
            byte[] buffer = new byte[64];
            int length = inputStream.read(buffer);
            if (length <= 0) {
                return -1;
            }
            String content = new String(buffer, 0, length, StandardCharsets.UTF_8).trim();
            return Integer.parseInt(content);
        } catch (Throwable th) {
            return -1;
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ignored) {
                    // 忽略
                }
            }
        }
    }

    private static void deleteQuietly(File file) {
        if (file == null) {
            return;
        }
        try {
            if (file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static void closeQuietly(Process process) {
        try {
            if (process.getInputStream() != null) {
                process.getInputStream().close();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        try {
            if (process.getErrorStream() != null) {
                process.getErrorStream().close();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        try {
            if (process.getOutputStream() != null) {
                process.getOutputStream().close();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}
