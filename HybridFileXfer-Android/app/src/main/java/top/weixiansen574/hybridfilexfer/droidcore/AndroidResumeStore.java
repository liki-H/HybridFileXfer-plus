package top.weixiansen574.hybridfilexfer.droidcore;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

import top.weixiansen574.hybridfilexfer.ConfigDB;
import top.weixiansen574.hybridfilexfer.core.BlockBitmap;
import top.weixiansen574.hybridfilexfer.core.ResumeEntry;
import top.weixiansen574.hybridfilexfer.core.ResumeStore;

/**
 * 续传清单的 Android 实现：复用 {@link ConfigDB}（库名 {@code config}，表 {@code resume_manifest}）。
 *
 * <p>契约 §3.2 / §3.5 的硬要求：<b>任何 SQLite 异常都必须被捕获并降级</b>（置 broken=true，后续调用全部 no-op，
 * 只打印一次栈），绝不能因为清单故障让传输失败。
 *
 * <p>本类只持有 ApplicationContext（经 ConfigDB 单例），不得持有 Activity。
 */
public class AndroidResumeStore implements ResumeStore {

    private final Context appContext;
    private volatile boolean broken;

    public AndroidResumeStore(Context context) {
        Context applicationContext = context == null ? null : context.getApplicationContext();
        this.appContext = applicationContext != null ? applicationContext : context;
    }

    private SQLiteDatabase db() {
        return ConfigDB.getInstance(appContext).getWritableDatabase();
    }

    private void markBroken(Throwable th) {
        if (!broken) {
            broken = true;
            th.printStackTrace();
        }
    }

    @Override
    public List<ResumeEntry> loadTask(String taskKey) {
        List<ResumeEntry> result = new ArrayList<>();
        if (broken || appContext == null || taskKey == null) {
            return result;
        }
        Cursor cursor = null;
        try {
            cursor = db().rawQuery(
                    "SELECT task_key, direction, remote_path, local_path, total_size, last_modified,"
                            + " block_count, bitmap, completed, updated_at"
                            + " FROM resume_manifest WHERE task_key = ? ORDER BY local_path",
                    new String[]{taskKey});
            while (cursor.moveToNext()) {
                String rowTaskKey = cursor.getString(0);
                int direction = cursor.getInt(1);
                String remotePath = cursor.getString(2);
                String localPath = cursor.getString(3);
                long totalSize = cursor.getLong(4);
                long lastModified = cursor.getLong(5);
                int blockCount = cursor.getInt(6);
                byte[] bitmapBytes = cursor.getBlob(7);
                boolean completed = cursor.getInt(8) != 0;
                long updatedAt = cursor.getLong(9);
                BlockBitmap bitmap;
                try {
                    bitmap = BlockBitmap.fromBytes(blockCount, bitmapBytes);
                } catch (IllegalArgumentException e) {
                    // 坏行：跳过（等价"无清单"），不拖垮传输
                    continue;
                }
                result.add(new ResumeEntry(rowTaskKey, direction,
                        remotePath == null ? "" : remotePath, localPath,
                        totalSize, lastModified, blockCount, bitmap, completed, updatedAt));
            }
        } catch (Throwable th) {
            markBroken(th);
            result.clear();
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        }
        return result;
    }

    @Override
    public void saveEntry(ResumeEntry entry) {
        if (broken || appContext == null || entry == null) {
            return;
        }
        try {
            ContentValues values = new ContentValues();
            values.put("task_key", entry.taskKey);
            values.put("direction", entry.direction);
            values.put("remote_path", entry.remotePath);
            values.put("local_path", entry.localPath);
            values.put("total_size", entry.totalSize);
            values.put("last_modified", entry.lastModified);
            values.put("block_count", entry.blockCount);
            values.put("bitmap", entry.bitmap.toBytes());
            values.put("completed", entry.completed ? 1 : 0);
            values.put("updated_at", entry.updatedAt);
            db().insertWithOnConflict("resume_manifest", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Throwable th) {
            markBroken(th);
        }
    }

    @Override
    public void removeEntry(String taskKey, String localPath) {
        if (broken || appContext == null || taskKey == null || localPath == null) {
            return;
        }
        try {
            db().delete("resume_manifest", "task_key = ? AND local_path = ?",
                    new String[]{taskKey, localPath});
        } catch (Throwable th) {
            markBroken(th);
        }
    }

    @Override
    public void removeTask(String taskKey) {
        if (broken || appContext == null || taskKey == null) {
            return;
        }
        try {
            db().delete("resume_manifest", "task_key = ?", new String[]{taskKey});
        } catch (Throwable th) {
            markBroken(th);
        }
    }

    @Override
    public void purgeExpired(long nowMillis, long retentionMillis) {
        if (broken || appContext == null) {
            return;
        }
        long threshold = nowMillis - retentionMillis;
        try {
            db().delete("resume_manifest", "updated_at < ?", new String[]{String.valueOf(threshold)});
        } catch (Throwable th) {
            markBroken(th);
        }
    }
}
