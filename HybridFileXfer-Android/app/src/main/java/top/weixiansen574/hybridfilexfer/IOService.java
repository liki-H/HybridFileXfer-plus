package top.weixiansen574.hybridfilexfer;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import rikka.shizuku.Shizuku;

public class IOService extends Service {
    private IOServiceImpl service;
    private static Shizuku.UserServiceArgs userServiceArgs;
    public IOService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        service = new IOServiceImpl();
        return service;
    }

    /**
     * 支持被 root watchdog（am start-service）以「启动式服务」拉起：
     * 返回 START_STICKY，进程被杀后系统有机会重建，便于下一次绑定。
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        // 启动式服务（root watchdog 拉起、从未被绑定）时 service 为 null，直接调用会 NPE
        if (service != null) {
            service.destroy();
            service = null;
        }
        super.onDestroy();
    }

    public static synchronized Shizuku.UserServiceArgs getUserServiceArgs(Context context){
        if (userServiceArgs == null) {
            // 用 ApplicationContext 构造，避免静态字段长期持有 Activity
            Context safeContext = context.getApplicationContext() != null
                    ? context.getApplicationContext() : context;
            userServiceArgs = new Shizuku.UserServiceArgs(new ComponentName(safeContext, IOServiceImpl.class))
                    .daemon(true)
                    .processNameSuffix("IOService")
                    .debuggable(false)
                    // 1 -> 2：IIOService.aidl 新增 getFileLength 后必须 bump，
                    // 否则残留的旧 daemon 进程会抛 TransactionException
                    .version(2);
        }
        return userServiceArgs;
    }
}
