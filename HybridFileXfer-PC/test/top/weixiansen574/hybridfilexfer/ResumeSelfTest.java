package top.weixiansen574.hybridfilexfer;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;

import top.weixiansen574.hybridfilexfer.core.BlockBitmap;
import top.weixiansen574.hybridfilexfer.core.ControllerIdentifiers;
import top.weixiansen574.hybridfilexfer.core.Crc32;
import top.weixiansen574.hybridfilexfer.core.FileBlock;
import top.weixiansen574.hybridfilexfer.core.ResumeEntry;
import top.weixiansen574.hybridfilexfer.core.ResumeProtocol;
import top.weixiansen574.hybridfilexfer.core.ResumeSession;
import top.weixiansen574.hybridfilexfer.core.ResumeSkipSet;
import top.weixiansen574.hybridfilexfer.core.ResumeStore;
import top.weixiansen574.hybridfilexfer.core.ResumeTaskKey;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.jdkcore.JdkReadFileCall;
import top.weixiansen574.hybridfilexfer.jdkcore.JdkResumeStore;
import top.weixiansen574.hybridfilexfer.jdkcore.JdkWriteFileCall;
import top.weixiansen574.nio.DataByteChannel;

/**
 * Resume self test (contract section 9.2).
 *
 * <p>This file is pure ASCII on purpose (non-ASCII paths are written with \\uXXXX escapes)
 * because the repository sources are UTF-8 but the Windows console is not.
 *
 * <p>Cases:
 * <ul>
 *     <li>T1 BlockBitmap</li>
 *     <li>T2 Crc32</li>
 *     <li>T3 ResumeTaskKey</li>
 *     <li>T4 resume negotiation codec</li>
 *     <li>T5 ReadFileCall skip set / T6 WriteFileCall resume write / T7 JdkResumeStore
 *     are executed from the same main() - they live in the jdkcore layer.</li>
 * </ul>
 * Run: see test/run-resume-selftest.ps1
 */
public final class ResumeSelfTest {

    private static int passed = 0;
    private static int failed = 0;
    private static final List<String> FAILURES = new ArrayList<String>();

    private interface Runner {
        void run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        runCase("T1 BlockBitmap", new Runner() {
            public void run() throws Exception {
                t1();
            }
        });
        runCase("T2 Crc32", new Runner() {
            public void run() throws Exception {
                t2();
            }
        });
        runCase("T3 ResumeTaskKey", new Runner() {
            public void run() throws Exception {
                t3();
            }
        });
        runCase("T4 resume codec", new Runner() {
            public void run() throws Exception {
                t4();
            }
        });
        runCase("T5 ReadFileCall skip set", new Runner() {
            public void run() throws Exception {
                t5();
            }
        });
        runCase("T6 WriteFileCall resume write", new Runner() {
            public void run() throws Exception {
                t6();
            }
        });
        runCase("T7 JdkResumeStore", new Runner() {
            public void run() throws Exception {
                t7();
            }
        });

        System.out.println();
        System.out.println("SELFTEST passed=" + passed + " failed=" + failed);
        for (int i = 0; i < FAILURES.size(); i++) {
            System.out.println("FAILED: " + FAILURES.get(i));
        }
        if (failed > 0) {
            System.out.println("SELFTEST FAIL");
            System.exit(1);
        }
        System.out.println("SELFTEST PASS");
    }

    private static void runCase(String name, Runner runner) {
        try {
            runner.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable th) {
            failed++;
            String detail = th.getClass().getSimpleName() + ": " + th.getMessage();
            FAILURES.add(name + " -> " + detail);
            System.out.println("FAIL " + name + " -> " + detail);
            th.printStackTrace(System.out);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    // ---------------------------------------------------------------- T1

    private static void t1() {
        check(BlockBitmap.byteLength(1) == 1, "byteLength(1)");
        check(BlockBitmap.byteLength(8) == 1, "byteLength(8)");
        check(BlockBitmap.byteLength(9) == 2, "byteLength(9)");
        check(BlockBitmap.byteLength(0) == 0, "byteLength(0)");

        BlockBitmap bitmap = new BlockBitmap(9);
        check(!bitmap.hasCompleted(), "fresh bitmap hasCompleted");
        check(!bitmap.isAllCompleted(), "fresh bitmap isAllCompleted");
        bitmap.setCompleted(8, true);
        check(bitmap.isCompleted(8), "isCompleted(8)");
        check(bitmap.toBytes()[1] == 0x01, "toBytes()[1] must be 0x01 (LSB first)");
        check(bitmap.hasCompleted(), "hasCompleted after one bit");
        check(!bitmap.isAllCompleted(), "not all completed yet");

        boolean thrown = false;
        try {
            BlockBitmap.fromBytes(9, new byte[1]);
        } catch (IllegalArgumentException e) {
            thrown = true;
        }
        check(thrown, "fromBytes(9, byte[1]) must throw IllegalArgumentException");

        // all bits set -> all completed; padding bits (9..15) must stay 0 and not matter
        BlockBitmap full = new BlockBitmap(9);
        for (int i = 0; i < 9; i++) {
            full.setCompleted(i, true);
        }
        check(full.isAllCompleted(), "all 9 bits completed");
        byte[] raw = full.toBytes();
        int padding = raw[1] & 0xFE;
        check(padding == 0, "padding bits must be zero, got 0x" + Integer.toHexString(raw[1] & 0xFF));

        // padding bits present in the byte stream must not affect isAllCompleted,
        // and fromBytes must normalize them to zero
        byte[] withPadding = new byte[]{(byte) 0xFF, (byte) 0xFF};
        BlockBitmap masked = BlockBitmap.fromBytes(9, withPadding);
        check(masked.isAllCompleted(), "padding bits must not block isAllCompleted");
        check(masked.toBytes()[1] == 0x01, "fromBytes must clear padding bits, got 0x"
                + Integer.toHexString(masked.toBytes()[1] & 0xFF));

        // out of range access is ignored / false
        BlockBitmap small = new BlockBitmap(4);
        small.setCompleted(9, true);
        check(!small.isCompleted(9), "out of range setCompleted ignored");
        check(!small.isCompleted(-1), "negative index is false");
    }

    // ---------------------------------------------------------------- T2

    private static void t2() {
        byte[] text = "123456789".getBytes(StandardCharsets.UTF_8);
        int standard = Crc32.compute(ByteBuffer.wrap(text), 0, text.length);
        check(standard == 0xCBF43926, "crc32(123456789) expected 0xCBF43926 got 0x"
                + Integer.toHexString(standard));

        // direct buffer as well (Android uses JNI direct buffers)
        ByteBuffer direct = ByteBuffer.allocateDirect(text.length);
        direct.put(text);
        int directCrc = Crc32.compute(direct, 0, text.length);
        check(directCrc == 0xCBF43926, "crc32 on direct buffer");

        // 1MB random data: random offset/length must match java.util.zip.CRC32,
        // and position()/limit() must not change
        Random random = new Random(20261026L);
        byte[] big = new byte[FileBlock.BLOCK_SIZE];
        random.nextBytes(big);
        ByteBuffer buffer = ByteBuffer.wrap(big);
        for (int round = 0; round < 32; round++) {
            int offset = random.nextInt(big.length);
            int length = 1 + random.nextInt(big.length - offset);
            int positionBefore = buffer.position();
            int limitBefore = buffer.limit();
            int actual = Crc32.compute(buffer, offset, length);
            check(buffer.position() == positionBefore, "position must not change");
            check(buffer.limit() == limitBefore, "limit must not change");
            CRC32 reference = new CRC32();
            reference.update(big, offset, length);
            check((int) reference.getValue() == actual, "crc mismatch at offset=" + offset
                    + " length=" + length);
        }
        // empty block
        check(Crc32.compute(ByteBuffer.wrap(new byte[0]), 0, 0) == 0, "crc of empty block is 0");
    }

    // ---------------------------------------------------------------- T3

    private static void t3() {
        Directory localUnix = new Directory("/sdcard", Directory.FILE_SYSTEM_UNIX);
        Directory remoteWin = new Directory("C:\\dst", Directory.FILE_SYSTEM_WINDOWS);

        RemoteFile a = new RemoteFile("a.txt", "/sdcard/a.txt", 1700000000000L, 1234L, false);
        RemoteFile b = new RemoteFile("b.txt", "/sdcard/b.txt", 1700000001000L, 4321L, false);
        RemoteFile dir = new RemoteFile("pics", "/sdcard/pics", 1700000002000L, 0L, true);

        List<RemoteFile> orderOne = new ArrayList<RemoteFile>();
        orderOne.add(a);
        orderOne.add(b);
        orderOne.add(dir);
        List<RemoteFile> orderTwo = new ArrayList<RemoteFile>();
        orderTwo.add(dir);
        orderTwo.add(b);
        orderTwo.add(a);

        String keyOne = ResumeTaskKey.compute(ResumeTaskKey.fromSelection(orderOne, localUnix, remoteWin));
        String keyTwo = ResumeTaskKey.compute(ResumeTaskKey.fromSelection(orderTwo, localUnix, remoteWin));
        check(keyOne.equals(keyTwo), "order must not change the task key");
        check(keyOne.length() == 32, "task key must be 32 hex chars, got " + keyOne.length());
        check(keyOne.equals(keyOne.toLowerCase()), "task key must be lowercase hex");
        for (int i = 0; i < keyOne.length(); i++) {
            char c = keyOne.charAt(i);
            check((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'), "task key must be hex: " + keyOne);
        }

        // top level file size changed -> different key
        RemoteFile aChanged = new RemoteFile("a.txt", "/sdcard/a.txt", 1700000000000L, 1235L, false);
        List<RemoteFile> changed = new ArrayList<RemoteFile>();
        changed.add(aChanged);
        changed.add(b);
        changed.add(dir);
        String keySize = ResumeTaskKey.compute(ResumeTaskKey.fromSelection(changed, localUnix, remoteWin));
        check(!keySize.equals(keyOne), "file size change must change the key");

        // directory mtime change -> same key (directories do not contribute size/mtime)
        RemoteFile dirChanged = new RemoteFile("pics", "/sdcard/pics", 1700000999000L, 999L, true);
        List<RemoteFile> dirMtime = new ArrayList<RemoteFile>();
        dirMtime.add(a);
        dirMtime.add(b);
        dirMtime.add(dirChanged);
        String keyDir = ResumeTaskKey.compute(ResumeTaskKey.fromSelection(dirMtime, localUnix, remoteWin));
        check(keyDir.equals(keyOne), "directory mtime must not change the key");

        // empty selection differs from "one empty directory"
        String emptyKey = ResumeTaskKey.compute(ResumeTaskKey.fromSelection(
                new ArrayList<RemoteFile>(), localUnix, remoteWin));
        List<RemoteFile> onlyDir = new ArrayList<RemoteFile>();
        onlyDir.add(dir);
        String onlyDirKey = ResumeTaskKey.compute(ResumeTaskKey.fromSelection(onlyDir, localUnix, remoteWin));
        check(!emptyKey.equals(onlyDirKey), "empty selection must differ from a single directory");
        check(emptyKey.length() == 32, "empty selection still yields a key");
    }

    // ---------------------------------------------------------------- T4

    private static void t4() throws Exception {
        // ---- RESUME_QUERY round trip ----
        SocketChannel[] pair = loopbackPair();
        DataByteChannel writer = new DataByteChannel(pair[0]);
        DataByteChannel reader = new DataByteChannel(pair[1]);

        List<ResumeTaskKey.Entry> entries = new ArrayList<ResumeTaskKey.Entry>();
        entries.add(new ResumeTaskKey.Entry(false, "C:\\dst\\pics", 0L, 0L));
        entries.add(new ResumeTaskKey.Entry(true, "C:\\dst\\a.txt", 3500000L, 1700000000000L));
        entries.add(new ResumeTaskKey.Entry(true, "C:\\dst\\\u4e2d\u6587 b.txt", 1L, 1700000001000L));

        ResumeProtocol.writeQuery(writer, "0123456789abcdef0123456789abcdef", true,
                "/sdcard", "C:\\dst", entries);
        writer.writeInt(424242); // sentinel written after the query, must stay in the stream

        ResumeProtocol.ResumeQuery query = ResumeProtocol.readQuery(reader);
        check(query.taskKey.equals("0123456789abcdef0123456789abcdef"), "taskKey round trip");
        check(query.crcEnabled, "crcEnabled round trip");
        check(query.senderRoot.equals("/sdcard"), "senderRoot round trip");
        check(query.destRoot.equals("C:\\dst"), "destRoot round trip");
        check(query.entries.size() == 3, "entry count round trip");
        for (int i = 0; i < entries.size(); i++) {
            ResumeTaskKey.Entry expected = entries.get(i);
            ResumeTaskKey.Entry actual = query.entries.get(i);
            check(expected.isFile == actual.isFile, "entry[" + i + "] isFile");
            check(expected.destPath.equals(actual.destPath), "entry[" + i + "] destPath");
            check(expected.totalSize == actual.totalSize, "entry[" + i + "] totalSize");
            check(expected.lastModified == actual.lastModified, "entry[" + i + "] lastModified");
        }
        check(reader.readInt() == 424242, "stream stays aligned after readQuery");
        pair[0].close();
        pair[1].close();

        // ---- RESUME_REPLY round trip (bitmap lengths 2 and 3, not multiples of 8) ----
        pair = loopbackPair();
        writer = new DataByteChannel(pair[0]);
        reader = new DataByteChannel(pair[1]);

        List<ResumeEntry> reply = new ArrayList<ResumeEntry>();
        BlockBitmap nine = new BlockBitmap(9);
        nine.setCompleted(0, true);
        nine.setCompleted(8, true);
        reply.add(new ResumeEntry("k", 0, "/sdcard", "C:\\dst\\a.txt", 9L * FileBlock.BLOCK_SIZE,
                1L, 9, nine, false, 1L));
        BlockBitmap seventeen = new BlockBitmap(17);
        seventeen.setCompleted(16, true);
        reply.add(new ResumeEntry("k", 0, "/sdcard", "C:\\dst\\\u4e2d\u6587.txt", 17L * FileBlock.BLOCK_SIZE,
                2L, 17, seventeen, false, 2L));

        ResumeProtocol.writeReply(writer, reply);
        Map<String, BlockBitmap> received = ResumeProtocol.readReply(reader);
        check(received.size() == 2, "reply size");
        BlockBitmap first = received.get("C:\\dst\\a.txt");
        check(first != null, "reply contains a.txt");
        check(first.blockCount() == 9, "a.txt blockCount");
        check(first.isCompleted(0) && first.isCompleted(8) && !first.isCompleted(1), "a.txt bitmap");
        BlockBitmap second = received.get("C:\\dst\\\u4e2d\u6587.txt");
        check(second != null, "reply contains the non-ASCII path");
        check(second.blockCount() == 17, "non-ASCII blockCount");
        check(second.isCompleted(16), "non-ASCII bitmap");
        pair[0].close();
        pair[1].close();

        // ---- skip set semantics driven by the reply ----
        ResumeSkipSet skipSet = new ResumeSkipSet(received);
        check(!skipSet.isSendable("C:\\dst\\a.txt", 0, 9), "completed block must not be resent");
        check(skipSet.isSendable("C:\\dst\\a.txt", 1, 9), "missing block must be sent");
        check(skipSet.isSendable("C:\\dst\\a.txt", 0, 10), "blockCount mismatch -> resend");
        check(!skipSet.isFileCompleted("C:\\dst\\a.txt", 9), "not fully completed");
        check(skipSet.isSendable("C:\\dst\\unknown.txt", 0, 9), "unknown path -> send");
        check(ResumeSkipSet.EMPTY.isSendable("x", 0, 1), "EMPTY skip set sends everything");

        // ---- wrong message number must throw IOException ----
        pair = loopbackPair();
        writer = new DataByteChannel(pair[0]);
        reader = new DataByteChannel(pair[1]);
        writer.writeShort((short) 99);
        expectIOException(reader, true);
        pair[0].close();
        pair[1].close();

        // ---- bitmap length inconsistent with blockCount must throw IOException ----
        pair = loopbackPair();
        writer = new DataByteChannel(pair[0]);
        reader = new DataByteChannel(pair[1]);
        writer.writeShort(ControllerIdentifiers.RESUME_REPLY);
        writer.writeInt(1);
        writer.writeUTF("C:\\dst\\bad.txt");
        writer.writeInt(9);   // blockCount -> needs 2 bytes
        writer.writeInt(1);   // byteLen (wrong on purpose)
        writer.write(new byte[]{0x01});
        expectIOException(reader, false);
        pair[0].close();
        pair[1].close();

        // ---- entryCount guard ----
        pair = loopbackPair();
        writer = new DataByteChannel(pair[0]);
        reader = new DataByteChannel(pair[1]);
        writer.writeShort(ControllerIdentifiers.RESUME_QUERY);
        writer.writeInt(-1);
        expectIOException(reader, true);
        pair[0].close();
        pair[1].close();
    }

    // ---------------------------------------------------------------- T5

    /** 3.5MB source file: 4 blocks, last one is 0.5MB. */
    private static void t5() throws Exception {
        File tmp = createTempDir("hfx-t5");
        File source = new File(tmp, "src.bin");
        int size = 3 * FileBlock.BLOCK_SIZE + FileBlock.BLOCK_SIZE / 2;
        writePattern(source, size);

        Directory localDir = new Directory(tmp.getAbsolutePath(), Directory.getCurrentFileSystem());
        Directory remoteDir = new Directory(new File(tmp, "dst").getAbsolutePath(),
                Directory.getCurrentFileSystem());
        RemoteFile remoteFile = new RemoteFile(source);
        List<RemoteFile> files = Collections.singletonList(remoteFile);
        String destPath = localDir.generateTransferPath(remoteFile.getPath(), remoteDir);
        int blockCount = FileBlock.calcBlockCount(size);
        check(blockCount == 4, "3.5MB must be 4 blocks, got " + blockCount);

        // case A: block 1 already completed -> only 0,2,3 are read
        BlockBitmap done = new BlockBitmap(blockCount);
        done.setCompleted(1, true);
        Map<String, BlockBitmap> completed = new HashMap<String, BlockBitmap>();
        completed.put(destPath, done);
        List<FileBlock> blocks = readBlocks(files, localDir, remoteDir, new ResumeSkipSet(completed));
        check(blocks.size() == 3, "expected 3 blocks, got " + blocks.size());
        check(blocks.get(0).index == 0 && blocks.get(1).index == 2 && blocks.get(2).index == 3,
                "block indexes must be [0,2,3]");
        check(blocks.get(0).getLength() == FileBlock.BLOCK_SIZE, "block0 length");
        check(blocks.get(1).getLength() == FileBlock.BLOCK_SIZE, "block2 length");
        check(blocks.get(2).getLength() == FileBlock.BLOCK_SIZE / 2, "block3 length");
        assertBlockMatchesSource(blocks.get(1), source, 2L * FileBlock.BLOCK_SIZE);

        // case B: everything completed -> no data block at all
        BlockBitmap all = new BlockBitmap(blockCount);
        for (int i = 0; i < blockCount; i++) {
            all.setCompleted(i, true);
        }
        Map<String, BlockBitmap> allMap = new HashMap<String, BlockBitmap>();
        allMap.put(destPath, all);
        List<FileBlock> none = readBlocks(files, localDir, remoteDir, new ResumeSkipSet(allMap));
        check(none.isEmpty(), "fully completed file must produce no block, got " + none.size());

        // case C: EMPTY skip set -> 4 blocks, all content correct
        List<FileBlock> full = readBlocks(files, localDir, remoteDir, ResumeSkipSet.EMPTY);
        check(full.size() == 4, "EMPTY skip set must send 4 blocks, got " + full.size());
        for (int i = 0; i < full.size(); i++) {
            assertBlockMatchesSource(full.get(i), source, (long) i * FileBlock.BLOCK_SIZE);
        }

        // case D: zero byte file -> exactly one zero-length block
        File empty = new File(tmp, "empty.bin");
        check(empty.createNewFile(), "create empty file");
        RemoteFile emptyFile = new RemoteFile(empty);
        List<FileBlock> zeroBlocks = readBlocks(Collections.singletonList(emptyFile), localDir, remoteDir,
                ResumeSkipSet.EMPTY);
        check(zeroBlocks.size() == 1, "zero byte file must produce 1 block, got " + zeroBlocks.size());
        check(zeroBlocks.get(0).getLength() == 0, "zero byte block length must be 0");
    }

    private static List<FileBlock> readBlocks(List<RemoteFile> files, Directory localDir, Directory remoteDir,
                                              ResumeSkipSet skipSet) throws Exception {
        LinkedBlockingDeque<ByteBuffer> buffers = new LinkedBlockingDeque<ByteBuffer>();
        for (int i = 0; i < 8; i++) {
            buffers.add(ByteBuffer.allocateDirect(FileBlock.BLOCK_SIZE));
        }
        JdkReadFileCall readCall = new JdkReadFileCall(buffers, files, localDir, remoteDir, 1);
        readCall.setSkipSet(skipSet);
        readCall.call();
        List<FileBlock> result = new ArrayList<FileBlock>();
        while (true) {
            FileBlock block = readCall.takeBlock();
            if (block.fileIndex == -1) {
                break;
            }
            result.add(block);
        }
        return result;
    }

    private static void assertBlockMatchesSource(FileBlock block, File source, long sourceOffset) throws Exception {
        RandomAccessFile file = new RandomAccessFile(source, "r");
        try {
            file.seek(sourceOffset);
            int length = block.getLength();
            byte[] expected = new byte[length];
            file.readFully(expected);
            for (int i = 0; i < length; i++) {
                check(block.data.get(i) == expected[i], "block " + block.index + " content mismatch at " + i);
            }
        } finally {
            file.close();
        }
    }

    // ---------------------------------------------------------------- T6

    private static void t6() throws Exception {
        File tmp = createTempDir("hfx-t6");
        File source = new File(tmp, "src.bin");
        int size = 3 * FileBlock.BLOCK_SIZE + FileBlock.BLOCK_SIZE / 2;
        writePattern(source, size);

        Directory localDir = new Directory(tmp.getAbsolutePath(), Directory.getCurrentFileSystem());
        Directory remoteDir = new Directory(new File(tmp, "dst").getAbsolutePath(),
                Directory.getCurrentFileSystem());
        RemoteFile remoteFile = new RemoteFile(source);
        List<RemoteFile> files = Collections.singletonList(remoteFile);
        List<FileBlock> allBlocks = readBlocks(files, localDir, remoteDir, ResumeSkipSet.EMPTY);
        check(allBlocks.size() == 4, "source must produce 4 blocks");

        // the write target is the destination path generated by the sender (not the local source dir)
        String destPath = localDir.generateTransferPath(remoteFile.getPath(), remoteDir);
        File target = new File(destPath);
        File targetParent = target.getParentFile();
        if (targetParent != null) {
            check(targetParent.isDirectory() || targetParent.mkdirs(), "create target parent dir");
        }
        fillWith(target, size, (byte) 0xFF);

        // partial delivery: blocks 0, 2, 3 only
        MemoryStore store = new MemoryStore();
        ResumeSession session = new ResumeSession("tk-t6", store);
        session.setRemoteRoot("/sdcard");
        JdkWriteFileCall writer = new JdkWriteFileCall(new LinkedBlockingDeque<ByteBuffer>(), 1);
        writer.setResumeSession(session);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread thread = new Thread(new WriteRunner(writer, failure), "t6-writer");
        thread.start();
        for (int i = 0; i < allBlocks.size(); i++) {
            if (allBlocks.get(i).index != 1) {
                writer.putBlock(allBlocks.get(i), 0);
            }
        }
        writer.finishChannel(0);
        thread.join(60000);
        if (failure.get() != null) {
            throw new AssertionError("writer failed: " + failure.get());
        }

        assertFileMatchesSource(target, source, 0L, FileBlock.BLOCK_SIZE);
        assertRangeAll(target, FileBlock.BLOCK_SIZE, FileBlock.BLOCK_SIZE, (byte) 0xFF);
        assertFileMatchesSource(target, source, 2L * FileBlock.BLOCK_SIZE, FileBlock.BLOCK_SIZE);
        assertFileMatchesSource(target, source, 3L * FileBlock.BLOCK_SIZE, FileBlock.BLOCK_SIZE / 2);

        // resume bookkeeping: bitmap must hold 0,2,3 and not 1
        check(store.entries.size() == 1, "one manifest row expected, got " + store.entries.size());
        ResumeEntry row = store.entries.get(destPath);
        check(row != null, "manifest row for " + destPath);
        check(row.blockCount == 4, "manifest blockCount");
        check(row.bitmap.isCompleted(0) && row.bitmap.isCompleted(2) && row.bitmap.isCompleted(3),
                "manifest bitmap must mark 0,2,3");
        check(!row.bitmap.isCompleted(1), "manifest bitmap must not mark block 1");
        check(!row.completed, "manifest row must not be completed yet");

        // second run: all 4 blocks -> file equals source
        List<FileBlock> again = readBlocks(files, localDir, remoteDir, ResumeSkipSet.EMPTY);
        JdkWriteFileCall writer2 = new JdkWriteFileCall(new LinkedBlockingDeque<ByteBuffer>(), 1);
        AtomicReference<Throwable> failure2 = new AtomicReference<Throwable>();
        Thread thread2 = new Thread(new WriteRunner(writer2, failure2), "t6-writer2");
        thread2.start();
        for (int i = 0; i < again.size(); i++) {
            writer2.putBlock(again.get(i), 0);
        }
        writer2.finishChannel(0);
        thread2.join(60000);
        if (failure2.get() != null) {
            throw new AssertionError("writer2 failed: " + failure2.get());
        }
        assertFileEquals(target, source);
    }

    private static final class WriteRunner implements Runnable {
        private final JdkWriteFileCall writer;
        private final AtomicReference<Throwable> failure;

        WriteRunner(JdkWriteFileCall writer, AtomicReference<Throwable> failure) {
            this.writer = writer;
            this.failure = failure;
        }

        public void run() {
            try {
                writer.call();
            } catch (Throwable th) {
                failure.set(th);
            }
        }
    }

    /** 内存版清单（T6 用来验证 ResumeSession + WriteFileCall 的位图推进） */
    private static final class MemoryStore implements ResumeStore {
        final Map<String, ResumeEntry> entries = new LinkedHashMap<String, ResumeEntry>();

        public List<ResumeEntry> loadTask(String taskKey) {
            return new ArrayList<ResumeEntry>(entries.values());
        }

        public void saveEntry(ResumeEntry entry) {
            entries.put(entry.localPath, entry);
        }

        public void removeEntry(String taskKey, String localPath) {
            entries.remove(localPath);
        }

        public void removeTask(String taskKey) {
            entries.clear();
        }

        public void purgeExpired(long nowMillis, long retentionMillis) {
        }
    }

    // ---------------------------------------------------------------- T7

    private static void t7() throws Exception {
        File dir = createTempDir("hfx-t7");
        System.setProperty(JdkResumeStore.DIR_PROPERTY, dir.getAbsolutePath());
        check(JdkResumeStore.resolveDir().getAbsolutePath().equals(dir.getAbsolutePath()),
                "hfx.resume.dir override must win");

        String taskKey = "0123456789abcdef0123456789abcdef";
        String trickyPath = "C:\\dst\\a\"b\\\u4e2d\u6587\\\u0001.txt";
        BlockBitmap nine = new BlockBitmap(9);
        nine.setCompleted(0, true);
        ResumeEntry first = new ResumeEntry(taskKey, 0, "C:\\pics", trickyPath,
                1234L, 1700000000000L, 9, nine, false, 1700000000123L);
        ResumeEntry second = new ResumeEntry(taskKey, 0, "C:\\pics", "C:\\dst\\plain.txt",
                99L, 1700000000001L, 1, new BlockBitmap(1), true, 1700000000999L);

        JdkResumeStore store = new JdkResumeStore();
        store.saveEntry(first);
        store.saveEntry(second);

        // fresh instance -> must read back from disk
        JdkResumeStore reader = new JdkResumeStore();
        List<ResumeEntry> loaded = reader.loadTask(taskKey);
        check(loaded.size() == 2, "loadTask size, got " + loaded.size());
        ResumeEntry loadedFirst = null;
        ResumeEntry loadedSecond = null;
        for (int i = 0; i < loaded.size(); i++) {
            ResumeEntry entry = loaded.get(i);
            if (entry.localPath.equals(trickyPath)) {
                loadedFirst = entry;
            } else if (entry.localPath.equals("C:\\dst\\plain.txt")) {
                loadedSecond = entry;
            }
        }
        check(loadedFirst != null, "tricky path entry must round trip");
        check(loadedSecond != null, "plain path entry must round trip");
        check(loadedFirst.taskKey.equals(taskKey), "taskKey round trip");
        check(loadedFirst.remotePath.equals("C:\\pics"), "remotePath round trip");
        check(loadedFirst.direction == 0, "direction round trip");
        check(loadedFirst.totalSize == 1234L, "totalSize round trip");
        check(loadedFirst.lastModified == 1700000000000L, "lastModified round trip");
        check(loadedFirst.blockCount == 9, "blockCount round trip");
        check(loadedFirst.updatedAt == 1700000000123L, "updatedAt round trip");
        check(!loadedFirst.completed, "completed round trip (false)");
        check(loadedFirst.bitmap.isCompleted(0) && !loadedFirst.bitmap.isCompleted(1), "bitmap round trip");
        check(loadedSecond.completed, "completed round trip (true)");

        // removeEntry -> 1 row left
        reader.removeEntry(taskKey, "C:\\dst\\plain.txt");
        check(new JdkResumeStore().loadTask(taskKey).size() == 1, "removeEntry");

        // removeTask -> 0 rows
        reader.removeTask(taskKey);
        check(new JdkResumeStore().loadTask(taskKey).isEmpty(), "removeTask");
        check(!new File(dir, taskKey + ".json").exists(), "removeTask must delete the json file");

        // purgeExpired: updatedAt = 0 is older than 7 days
        JdkResumeStore writer = new JdkResumeStore();
        writer.saveEntry(new ResumeEntry(taskKey, 0, "C:\\pics", "C:\\dst\\old.txt",
                1L, 1L, 1, new BlockBitmap(1), false, 0L));
        JdkResumeStore purger = new JdkResumeStore();
        purger.purgeExpired(System.currentTimeMillis(), ResumeSession.RETENTION_MS);
        check(new JdkResumeStore().loadTask(taskKey).isEmpty(), "purgeExpired must drop stale rows");

        // broken json -> empty result, no exception, file removed
        JdkResumeStore brokenWriter = new JdkResumeStore();
        brokenWriter.saveEntry(second);
        File manifest = new File(dir, taskKey + ".json");
        check(manifest.isFile(), "manifest file must exist before corruption");
        Files.write(manifest.toPath(), "{{{ not json".getBytes(StandardCharsets.UTF_8));
        JdkResumeStore brokenReader = new JdkResumeStore();
        check(brokenReader.loadTask(taskKey).isEmpty(), "broken manifest must behave like no manifest");
        check(!manifest.exists(), "broken manifest file must be deleted");

        System.clearProperty(JdkResumeStore.DIR_PROPERTY);
    }

    private static File createTempDir(String prefix) throws Exception {
        File dir = Files.createTempDirectory(prefix).toFile();
        check(dir.isDirectory(), "temp dir created: " + dir);
        return dir;
    }

    private static void writePattern(File file, int size) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        try {
            raf.setLength(0);
            byte[] chunk = new byte[64 * 1024];
            int written = 0;
            while (written < size) {
                int length = Math.min(chunk.length, size - written);
                for (int i = 0; i < length; i++) {
                    chunk[i] = (byte) ((written + i) * 31 + 7);
                }
                raf.write(chunk, 0, length);
                written += length;
            }
        } finally {
            raf.close();
        }
    }

    private static void fillWith(File file, long size, byte value) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        try {
            raf.setLength(0);
            raf.setLength(size);
            byte[] chunk = new byte[64 * 1024];
            Arrays.fill(chunk, value);
            long written = 0;
            while (written < size) {
                int length = (int) Math.min(chunk.length, size - written);
                raf.write(chunk, 0, length);
                written += length;
            }
        } finally {
            raf.close();
        }
    }

    private static void assertFileMatchesSource(File target, File source, long offset, int length)
            throws Exception {
        RandomAccessFile sourceFile = new RandomAccessFile(source, "r");
        RandomAccessFile targetFile = new RandomAccessFile(target, "r");
        try {
            sourceFile.seek(offset);
            targetFile.seek(offset);
            byte[] expected = new byte[length];
            byte[] actual = new byte[length];
            sourceFile.readFully(expected);
            targetFile.readFully(actual);
            for (int i = 0; i < length; i++) {
                check(actual[i] == expected[i],
                        "content mismatch at offset " + (offset + i) + " in " + target.getName());
            }
        } finally {
            sourceFile.close();
            targetFile.close();
        }
    }

    private static void assertRangeAll(File target, long offset, int length, byte value) throws Exception {
        RandomAccessFile file = new RandomAccessFile(target, "r");
        try {
            file.seek(offset);
            byte[] actual = new byte[length];
            file.readFully(actual);
            for (int i = 0; i < length; i++) {
                check(actual[i] == value, "expected untouched byte 0x" + Integer.toHexString(value & 0xFF)
                        + " at offset " + (offset + i) + " in " + target.getName());
            }
        } finally {
            file.close();
        }
    }

    private static void assertFileEquals(File target, File source) throws Exception {
        long size = source.length();
        check(target.length() == size, "file size mismatch: " + target.length() + " vs " + size);
        assertFileMatchesSource(target, source, 0L, (int) size);
    }

    private static void expectIOException(DataByteChannel channel, boolean query) {        boolean thrown = false;
        try {
            if (query) {
                ResumeProtocol.readQuery(channel);
            } else {
                ResumeProtocol.readReply(channel);
            }
        } catch (IOException e) {
            thrown = true;
        }
        check(thrown, "expected IOException from " + (query ? "readQuery" : "readReply"));
    }

    private static SocketChannel[] loopbackPair() throws Exception {
        ServerSocketChannel server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        SocketChannel client = SocketChannel.open(server.getLocalAddress());
        SocketChannel accepted = server.accept();
        server.close();
        client.configureBlocking(true);
        accepted.configureBlocking(true);
        return new SocketChannel[]{client, accepted};
    }

    private ResumeSelfTest() {
    }
}
