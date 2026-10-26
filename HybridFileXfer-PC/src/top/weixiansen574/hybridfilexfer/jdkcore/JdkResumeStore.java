package top.weixiansen574.hybridfilexfer.jdkcore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import top.weixiansen574.hybridfilexfer.core.BlockBitmap;
import top.weixiansen574.hybridfilexfer.core.ResumeEntry;
import top.weixiansen574.hybridfilexfer.core.ResumeStore;

/**
 * 续传清单的 PC 实现（契约 §3.3）：一个任务一个 JSON 文件。
 *
 * <p>目录：{@code -Dhfx.resume.dir} 或 {@code ~/.hybridfilexfer/resume}。
 * <p>写入用"临时文件 + 原子替换"；<b>解析失败/字段缺失 → 删掉该文件并按"无清单"处理</b>（坏文件不许拖垮传输）。
 * <p>手写 IO，不引入第三方 JSON 库（core 侧禁止 {@code java.util.Base64}，但 PC 专属层允许）。
 */
public class JdkResumeStore implements ResumeStore {

    /** 覆盖清单目录的 system property（测试用） */
    public static final String DIR_PROPERTY = "hfx.resume.dir";

    private static final int FORMAT_VERSION = 1;

    private final File dir;
    /** taskKey -> (localPath -> entry)，避免每次保存都重新解析整个文件 */
    private final Map<String, Map<String, ResumeEntry>> cache = new HashMap<>();
    private volatile boolean broken;

    public JdkResumeStore() {
        this(resolveDir());
    }

    public JdkResumeStore(File dir) {
        this.dir = dir;
    }

    public static File resolveDir() {
        String override = System.getProperty(DIR_PROPERTY);
        if (override != null && override.trim().length() > 0) {
            return new File(override.trim());
        }
        return new File(System.getProperty("user.home"),
                ".hybridfilexfer" + File.separator + "resume");
    }

    public File getDir() {
        return dir;
    }

    private void markBroken(Throwable th) {
        if (!broken) {
            broken = true;
            th.printStackTrace();
        }
    }

    private File taskFile(String taskKey) {
        return new File(dir, sanitize(taskKey) + ".json");
    }

    private static String sanitize(String taskKey) {
        if (taskKey == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder(taskKey.length());
        for (int i = 0; i < taskKey.length(); i++) {
            char c = taskKey.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z') || c == '-' || c == '_';
            builder.append(ok ? c : '_');
        }
        return builder.toString();
    }

    // ------------------------------------------------------------------ read

    @Override
    public synchronized List<ResumeEntry> loadTask(String taskKey) {
        List<ResumeEntry> result = new ArrayList<>();
        if (broken || taskKey == null) {
            return result;
        }
        Map<String, ResumeEntry> entries = entriesOf(taskKey);
        result.addAll(entries.values());
        return result;
    }

    private Map<String, ResumeEntry> entriesOf(String taskKey) {
        Map<String, ResumeEntry> cached = cache.get(taskKey);
        if (cached != null) {
            return cached;
        }
        Map<String, ResumeEntry> loaded = loadFromDisk(taskKey);
        cache.put(taskKey, loaded);
        return loaded;
    }

    private Map<String, ResumeEntry> loadFromDisk(String taskKey) {
        Map<String, ResumeEntry> result = new LinkedHashMap<>();
        File file = taskFile(taskKey);
        if (!file.isFile()) {
            return result;
        }
        String text;
        try {
            text = readAll(file);
        } catch (Throwable th) {
            markBroken(th);
            return result;
        }
        try {
            Object rootValue = new Parser(text).parseDocument();
            if (!(rootValue instanceof Map)) {
                throw new IOException("manifest root is not an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) rootValue;
            Object entriesValue = root.get("entries");
            if (!(entriesValue instanceof List)) {
                throw new IOException("manifest entries missing");
            }
            @SuppressWarnings("unchecked")
            List<Object> entries = (List<Object>) entriesValue;
            String remotePath = asString(root.get("remotePath"), "");
            int direction = (int) asLong(root.get("direction"), 0L);
            String fileTaskKey = asString(root.get("taskKey"), taskKey);
            for (int i = 0; i < entries.size(); i++) {
                Object itemValue = entries.get(i);
                if (!(itemValue instanceof Map)) {
                    throw new IOException("manifest entry is not an object");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> item = (Map<String, Object>) itemValue;
                String localPath = asString(item.get("localPath"), null);
                if (localPath == null) {
                    throw new IOException("manifest entry without localPath");
                }
                long totalSize = asLong(item.get("totalSize"), 0L);
                long lastModified = asLong(item.get("lastModified"), 0L);
                int blockCount = (int) asLong(item.get("blockCount"), 0L);
                int completed = (int) asLong(item.get("completed"), 0L);
                long updatedAt = asLong(item.get("updatedAt"), 0L);
                String bitmapText = asString(item.get("bitmap"), null);
                if (bitmapText == null) {
                    throw new IOException("manifest entry without bitmap");
                }
                byte[] bitmapBytes = Base64.getDecoder().decode(bitmapText);
                BlockBitmap bitmap = BlockBitmap.fromBytes(blockCount, bitmapBytes);
                result.put(localPath, new ResumeEntry(fileTaskKey, direction, remotePath,
                        localPath, totalSize, lastModified, blockCount, bitmap,
                        completed != 0, updatedAt));
            }
            return result;
        } catch (Throwable th) {
            // 坏文件：删掉并按"无清单"处理
            System.err.println("Warning: broken resume manifest removed: " + file);
            deleteQuietly(file);
            return new LinkedHashMap<>();
        }
    }

    private static String readAll(File file) throws IOException {
        InputStream inputStream = null;
        try {
            inputStream = new FileInputStream(file);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream(Math.max(64, (int) file.length()));
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, read);
            }
            return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
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

    private static String asString(Object value, String fallback) {
        return value instanceof String ? (String) value : fallback;
    }

    private static long asLong(Object value, long fallback) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return fallback;
    }

    // ----------------------------------------------------------------- write

    @Override
    public synchronized void saveEntry(ResumeEntry entry) {
        if (broken || entry == null || entry.taskKey == null) {
            return;
        }
        try {
            Map<String, ResumeEntry> entries = entriesOf(entry.taskKey);
            entries.put(entry.localPath, entry);
            writeTask(entry.taskKey, entries);
        } catch (Throwable th) {
            markBroken(th);
        }
    }

    @Override
    public synchronized void removeEntry(String taskKey, String localPath) {
        if (broken || taskKey == null || localPath == null) {
            return;
        }
        try {
            Map<String, ResumeEntry> entries = entriesOf(taskKey);
            entries.remove(localPath);
            writeTask(taskKey, entries);
        } catch (Throwable th) {
            markBroken(th);
        }
    }

    @Override
    public synchronized void removeTask(String taskKey) {
        if (broken || taskKey == null) {
            return;
        }
        try {
            cache.remove(taskKey);
            deleteQuietly(taskFile(taskKey));
        } catch (Throwable th) {
            markBroken(th);
        }
    }

    @Override
    public synchronized void purgeExpired(long nowMillis, long retentionMillis) {
        if (broken) {
            return;
        }
        long threshold = nowMillis - retentionMillis;
        try {
            File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            for (int i = 0; i < files.length; i++) {
                File file = files[i];
                if (!file.isFile() || !file.getName().endsWith(".json")) {
                    continue;
                }
                String taskKey = file.getName().substring(0, file.getName().length() - 5);
                Map<String, ResumeEntry> entries = loadFromDisk(taskKey);
                boolean changed = false;
                List<String> toRemove = new ArrayList<>();
                for (Map.Entry<String, ResumeEntry> mapEntry : entries.entrySet()) {
                    if (mapEntry.getValue().updatedAt < threshold) {
                        toRemove.add(mapEntry.getKey());
                    }
                }
                for (int j = 0; j < toRemove.size(); j++) {
                    entries.remove(toRemove.get(j));
                    changed = true;
                }
                if (changed) {
                    if (entries.isEmpty()) {
                        deleteQuietly(file);
                    } else {
                        writeTask(taskKey, entries);
                    }
                }
                cache.put(taskKey, entries);
            }
        } catch (Throwable th) {
            markBroken(th);
        }
    }

    private void writeTask(String taskKey, Map<String, ResumeEntry> entries) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("cannot create dir " + dir);
        }
        File target = taskFile(taskKey);
        if (entries.isEmpty()) {
            deleteQuietly(target);
            return;
        }
        String text = toJson(taskKey, entries);
        File tmp = new File(dir, target.getName() + ".tmp");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        OutputStream outputStream = null;
        try {
            outputStream = new FileOutputStream(tmp, false);
            outputStream.write(bytes);
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
        Path source = tmp.toPath();
        Path destination = target.toPath();
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable atomicFailed) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String toJson(String taskKey, Map<String, ResumeEntry> entries) {
        StringBuilder builder = new StringBuilder(256 + entries.size() * 256);
        builder.append("{\"version\":").append(FORMAT_VERSION);
        builder.append(",\"taskKey\":");
        appendString(builder, taskKey);
        int direction = 0;
        String remotePath = "";
        for (Map.Entry<String, ResumeEntry> mapEntry : entries.entrySet()) {
            direction = mapEntry.getValue().direction;
            remotePath = mapEntry.getValue().remotePath;
            break;
        }
        builder.append(",\"direction\":").append(direction);
        builder.append(",\"remotePath\":");
        appendString(builder, remotePath);
        builder.append(",\"entries\":[");
        boolean first = true;
        for (Map.Entry<String, ResumeEntry> mapEntry : entries.entrySet()) {
            ResumeEntry entry = mapEntry.getValue();
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append("\n{\"localPath\":");
            appendString(builder, entry.localPath);
            builder.append(",\"totalSize\":").append(entry.totalSize);
            builder.append(",\"lastModified\":").append(entry.lastModified);
            builder.append(",\"blockCount\":").append(entry.blockCount);
            builder.append(",\"completed\":").append(entry.completed ? 1 : 0);
            builder.append(",\"updatedAt\":").append(entry.updatedAt);
            builder.append(",\"bitmap\":");
            appendString(builder, Base64.getEncoder().encodeToString(entry.bitmap.toBytes()));
            builder.append('}');
        }
        builder.append("]}\n");
        return builder.toString();
    }

    /**
     * 唯一允许的转义子集：双引号 -&gt; 反斜杠+双引号；反斜杠 -&gt; 双反斜杠；
     * U+0000..U+001F 的控制字符 -&gt; 反斜杠+u00XX（小写两位十六进制）；其它字符原样 UTF-8。
     */
    private static void appendString(StringBuilder builder, String value) {
        builder.append('"');
        if (value != null) {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '"') {
                    builder.append("\\\"");
                } else if (c == '\\') {
                    builder.append("\\\\");
                } else if (c < 0x20) {
                    builder.append("\\u00");
                    String hex = Integer.toHexString(c);
                    if (hex.length() < 2) {
                        builder.append('0');
                    }
                    builder.append(hex);
                } else {
                    builder.append(c);
                }
            }
        }
        builder.append('"');
    }

    private static void deleteQuietly(File file) {
        if (file == null) {
            return;
        }
        try {
            if (file.exists() && !file.delete()) {
                System.err.println("Warning: cannot delete " + file);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /**
     * 极简 JSON 解析器（只覆盖清单用到的子集：object / array / string / number / true / false / null）。
     *
     * <p>字符串必须支持标准转义（双引号、反斜杠、斜杠、uXXXX 形式），其中斜杠转义只读不写。
     */
    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        Object parseDocument() throws IOException {
            Object value = parseValue();
            skipWhitespace();
            if (pos != text.length()) {
                throw new IOException("trailing data at " + pos);
            }
            return value;
        }

        private void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private char peek() throws IOException {
            if (pos >= text.length()) {
                throw new IOException("unexpected end of json");
            }
            return text.charAt(pos);
        }

        private Object parseValue() throws IOException {
            skipWhitespace();
            char c = peek();
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == '"') {
                return parseString();
            }
            if (c == 't') {
                expect("true");
                return Boolean.TRUE;
            }
            if (c == 'f') {
                expect("false");
                return Boolean.FALSE;
            }
            if (c == 'n') {
                expect("null");
                return null;
            }
            return parseNumber();
        }

        private void expect(String literal) throws IOException {
            if (!text.startsWith(literal, pos)) {
                throw new IOException("expected " + literal + " at " + pos);
            }
            pos += literal.length();
        }

        private Map<String, Object> parseObject() throws IOException {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                if (peek() != ':') {
                    throw new IOException("expected ':' at " + pos);
                }
                pos++;
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    return map;
                }
                throw new IOException("expected ',' or '}' at " + pos);
            }
        }

        private List<Object> parseArray() throws IOException {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    return list;
                }
                throw new IOException("expected ',' or ']' at " + pos);
            }
        }

        private String parseString() throws IOException {
            if (peek() != '"') {
                throw new IOException("expected string at " + pos);
            }
            pos++;
            StringBuilder builder = new StringBuilder();
            while (true) {
                if (pos >= text.length()) {
                    throw new IOException("unterminated string");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return builder.toString();
                }
                if (c != '\\') {
                    builder.append(c);
                    continue;
                }
                if (pos >= text.length()) {
                    throw new IOException("unterminated escape");
                }
                char escape = text.charAt(pos++);
                switch (escape) {
                    case '"':
                        builder.append('"');
                        break;
                    case '\\':
                        builder.append('\\');
                        break;
                    case '/':
                        builder.append('/');
                        break;
                    case 'b':
                        builder.append('\b');
                        break;
                    case 'f':
                        builder.append('\f');
                        break;
                    case 'n':
                        builder.append('\n');
                        break;
                    case 'r':
                        builder.append('\r');
                        break;
                    case 't':
                        builder.append('\t');
                        break;
                    case 'u':
                        if (pos + 4 > text.length()) {
                            throw new IOException("bad \\u escape");
                        }
                        String hex = text.substring(pos, pos + 4);
                        pos += 4;
                        try {
                            builder.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw new IOException("bad \\u escape: " + hex);
                        }
                        break;
                    default:
                        throw new IOException("unsupported escape: \\" + escape);
                }
            }
        }

        private Object parseNumber() throws IOException {
            int start = pos;
            if (pos < text.length() && (text.charAt(pos) == '-' || text.charAt(pos) == '+')) {
                pos++;
            }
            boolean floating = false;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') {
                    floating = floating || c == '.' || c == 'e' || c == 'E';
                    pos++;
                } else {
                    break;
                }
            }
            String number = text.substring(start, pos);
            if (number.isEmpty()) {
                throw new IOException("expected number at " + start);
            }
            if (floating) {
                return Double.valueOf(number);
            }
            return Long.valueOf(number);
        }
    }
}
