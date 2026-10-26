package top.weixiansen574.hybridfilexfer;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import top.weixiansen574.hybridfilexfer.bean.BookMark;

@SuppressLint("Range")
public class ConfigDB extends SQLiteOpenHelper {
    /** 2：新增续传清单表 resume_manifest（需求B） */
    public static final int VERSION = 2;

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

    private static ConfigDB instance;
    private ConfigDB(@Nullable Context context) {
        super(context, "config", null, VERSION);
    }

    public synchronized static ConfigDB getInstance(Context context){
        if (instance == null){
            instance = new ConfigDB(context.getApplicationContext());
        }
        return instance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE local_dir_bookmarks (\n" +
                "    id   INTEGER PRIMARY KEY AUTOINCREMENT\n" +
                "                 NOT NULL,\n" +
                "    path TEXT    NOT NULL\n" +
                "                 UNIQUE\n" +
                ");");
        db.execSQL("CREATE TABLE remote_dir_bookmarks (\n" +
                "    id   INTEGER PRIMARY KEY AUTOINCREMENT\n" +
                "                 NOT NULL,\n" +
                "    path TEXT    UNIQUE\n" +
                "                 NOT NULL\n" +
                ");");
        db.execSQL(SQL_CREATE_RESUME);
        db.execSQL(SQL_CREATE_RESUME_INDEX);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 老用户从 v1 库升级不会走 onCreate，必须在此建续传表
        if (oldVersion < 2) {
            db.execSQL(SQL_CREATE_RESUME);
            db.execSQL(SQL_CREATE_RESUME_INDEX);
        }
    }

    public long addLocalBookmark(String path) {
        ContentValues cv = new ContentValues();
        cv.put("path", path);
        return getWritableDatabase()
                .insert("local_dir_bookmarks", null, cv);

    }

    public long addRemoteBookmark(String path) {
        ContentValues cv = new ContentValues();
        cv.put("path", path);
        return getWritableDatabase()
                .insert("remote_dir_bookmarks", null, cv);
    }

    public boolean checkLocalBookmarkExists(String path) {
        Cursor cursor = getReadableDatabase()
                .rawQuery("SELECT * FROM local_dir_bookmarks WHERE path = ?", new String[]{path});
        boolean exists = cursor.moveToFirst();
        cursor.close();
        return exists;
    }

    public boolean checkRemoteBookmarkExists(String path) {
        Cursor cursor = getReadableDatabase()
                .rawQuery("SELECT * FROM remote_dir_bookmarks WHERE path = ?", new String[]{path});
        boolean exists = cursor.moveToFirst();
        cursor.close();
        return exists;
    }


    public long removeLocalBookmark(int id) {
        return getWritableDatabase()
                .delete("local_dir_bookmarks", "id = ?", new String[]{String.valueOf(id)});
    }

    public long removeRemoteBookmark(int id) {
        return getWritableDatabase()
                .delete("remote_dir_bookmarks", "id = ?", new String[]{String.valueOf(id)});
    }


    public List<BookMark> getAllLocalBookmark() {
        return getAllBookmarks("local_dir_bookmarks");
    }

    public List<BookMark> getAllRemoteBookmark() {
        return getAllBookmarks("remote_dir_bookmarks");
    }

    private List<BookMark> getAllBookmarks(String tableName){
        Cursor cursor = getReadableDatabase().rawQuery("SELECT * FROM "+tableName, null);
        List<BookMark> bookmarks = new ArrayList<>(cursor.getCount());
        while (cursor.moveToNext()) {
            bookmarks.add(
                    new BookMark(
                            cursor.getInt(cursor.getColumnIndex("id")),
                            cursor.getString(cursor.getColumnIndex("path"))
                    ));
        }
        cursor.close();
        return bookmarks;
    }
}
