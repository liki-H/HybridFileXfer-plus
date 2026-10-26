package top.weixiansen574.hybridfilexfer.foreground;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import top.weixiansen574.hybridfilexfer.MainActivity;
import top.weixiansen574.hybridfilexfer.R;

/**
 * 前台保活服务：让主进程（HFXServer / socket / 传输线程）在息屏、切到后台后不被系统回收。
 *
 * <p>设计约定（需求C）：
 * <ul>
 *     <li>运行在主进程（Manifest 中不指定 android:process），与被保活对象同进程；</li>
 *     <li>foregroundServiceType = dataSync，通知渠道固定为 {@link #CHANNEL_ID}；</li>
 *     <li>onCreate 获取 PARTIAL_WAKE_LOCK，onDestroy 释放，严格成对；</li>
 *     <li>root 模式下同时启动 {@link RootWatchdog} 守护 :IOService 进程；</li>
 *     <li>外部只通过 {@link #start(Context)} / {@link #start(Context, boolean)} / {@link #stop(Context)}
 *     静态方法操作，内部一律使用 ApplicationContext，避免持有 Activity。</li>
 * </ul>
 *
 * <p>注意：本机无 Android SDK，未能编译验证，API 版本分支已按 minSdk 24 / compileSdk 34 写全。
 */
public class TransferForegroundService extends Service {

    /** 通知渠道 id */
    public static final String CHANNEL_ID = "transfer_channel";
    /** 前台通知 id */
    public static final int NOTIFICATION_ID = 1;

    private static final String EXTRA_ROOT_MODE = "extra_root_mode";
    private static final String WAKE_LOCK_TAG = "HybridFileXfer:TransferForegroundService";

    /**
     * 最近一次 start 时的 root 模式标记。
     * 进程被系统杀掉后以 START_STICKY 重建时 intent 可能为 null，用该字段兜底。
     */
    private static volatile boolean lastRootMode = false;

    private PowerManager.WakeLock wakeLock;
    private boolean foregroundStarted = false;

    /** 启动前台保活服务（非 root 模式）。 */
    public static void start(Context context) {
        start(context, false);
    }

    /**
     * 启动前台保活服务。
     *
     * @param context  任意 Context，内部会转成 ApplicationContext
     * @param rootMode 是否以 root 模式运行（为 true 时同时拉起 RootWatchdog）
     */
    public static void start(Context context, boolean rootMode) {
        Context appContext = toApplicationContext(context);
        if (appContext == null) {
            return;
        }
        lastRootMode = rootMode;
        Intent intent = new Intent(appContext, TransferForegroundService.class);
        intent.putExtra(EXTRA_ROOT_MODE, rootMode);
        try {
            ContextCompat.startForegroundService(appContext, intent);
        } catch (Throwable th) {
            // API 31+ 允许前台启动的时机受限（BackgroundStartNotAllowed）、
            // 或厂商 ROM 拦截：静默降级，不影响传输功能本身。
            th.printStackTrace();
        }
    }

    /** 停止前台保活服务（同时会停止 RootWatchdog）。 */
    public static void stop(Context context) {
        Context appContext = toApplicationContext(context);
        if (appContext == null) {
            return;
        }
        try {
            appContext.stopService(new Intent(appContext, TransferForegroundService.class));
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private static Context toApplicationContext(Context context) {
        if (context == null) {
            return null;
        }
        Context appContext = context.getApplicationContext();
        return appContext != null ? appContext : context;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // 先占住唤醒锁，保证 onCreate 之后到 onStartCommand 之间不被休眠打断
        acquireWakeLock();
        // startForegroundService 要求 5s 内调用 startForeground，放在 onCreate 最稳
        showForegroundNotification(lastRootMode);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean rootMode = lastRootMode;
        if (intent != null && intent.hasExtra(EXTRA_ROOT_MODE)) {
            rootMode = intent.getBooleanExtra(EXTRA_ROOT_MODE, false);
            lastRootMode = rootMode;
        }
        if (!foregroundStarted) {
            showForegroundNotification(rootMode);
        }
        if (rootMode) {
            RootWatchdog.startIfRoot(this);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // 不允许绑定：保活服务只作为常驻服务存在
        return null;
    }

    @Override
    public void onDestroy() {
        // 与 onCreate 的 acquireWakeLock() 成对
        releaseWakeLock();
        // 与 onStartCommand 中的 startIfRoot() 成对
        RootWatchdog.stop();
        foregroundStarted = false;
        super.onDestroy();
    }

    private void acquireWakeLock() {
        if (wakeLock != null) {
            return;
        }
        try {
            PowerManager powerManager = ContextCompat.getSystemService(this, PowerManager.class);
            if (powerManager == null) {
                return;
            }
            PowerManager.WakeLock newWakeLock =
                    powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG);
            // 不做引用计数，避免 release 次数不匹配导致唤醒锁泄漏
            newWakeLock.setReferenceCounted(false);
            newWakeLock.acquire();
            wakeLock = newWakeLock;
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private void releaseWakeLock() {
        PowerManager.WakeLock heldWakeLock = wakeLock;
        wakeLock = null;
        if (heldWakeLock == null) {
            return;
        }
        try {
            if (heldWakeLock.isHeld()) {
                heldWakeLock.release();
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private void showForegroundNotification(boolean rootMode) {
        try {
            createNotificationChannel();
            Notification notification = buildNotification(rootMode);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29+ 需要带 type，且 Manifest 里已声明 android:foregroundServiceType="dataSync"
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            foregroundStarted = true;
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        try {
            NotificationManager notificationManager =
                    ContextCompat.getSystemService(this, NotificationManager.class);
            if (notificationManager == null) {
                return;
            }
            if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) {
                return;
            }
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.keepalive_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.keepalive_notification_channel_description));
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private Notification buildNotification(boolean rootMode) {
        int textRes = rootMode
                ? R.string.keepalive_notification_text_root
                : R.string.keepalive_notification_text;
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.baseline_speed_24)
                .setContentTitle(getString(R.string.keepalive_notification_title))
                .setContentText(getString(textRes))
                .setContentIntent(buildContentIntent())
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build();
    }

    private PendingIntent buildContentIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
