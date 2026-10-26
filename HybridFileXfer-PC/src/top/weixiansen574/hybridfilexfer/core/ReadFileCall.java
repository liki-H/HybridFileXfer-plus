package top.weixiansen574.hybridfilexfer.core;

import java.io.EOFException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

public abstract class ReadFileCall implements Callable<Void> {
    public static final FileBlock END_POINT = new FileBlock(true, -1, "END_POINT", 0, 0, -1, null);
    public static final FileBlock INTERRUPT = new FileBlock(true, -1, "INTERRUPT", 0, 0, -1, null);
    public static final FileBlock READ_ERROR = new FileBlock(true, -1, "READ_ERROR", 0, 0, -1, null);
    public static final FileBlock WRITE_ERROR = new FileBlock(true, -1, "WRITE_ERROR", 0, 0, -1, null);

    private final LinkedBlockingDeque<FileBlock> deque = new LinkedBlockingDeque<>();
    private final LinkedBlockingDeque<ByteBuffer> buffers;
    private final List<RemoteFile> files;
    private final Directory localDir;
    private final Directory remoteDir;
    private final int operateThreadCount;
    private int fileIndex = -1;

    /** 跳过集（接收端回执的已完成块）；默认全发 */
    private ResumeSkipSet skipSet = ResumeSkipSet.EMPTY;
    /** 是否每块带 CRC32（发送方决定） */
    private boolean crcEnabled = false;
    /** 门控（暂停/恢复） */
    private TransferGate gate = TransferGate.NOOP;

    public ReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files, Directory localDir, Directory remoteDir, int operateThreadCount) {
        this.buffers = buffers;
        this.files = files;
        this.localDir = localDir;
        this.remoteDir = remoteDir;
        this.operateThreadCount = operateThreadCount;
    }

    public void setSkipSet(ResumeSkipSet skipSet) {
        this.skipSet = (skipSet == null) ? ResumeSkipSet.EMPTY : skipSet;
    }

    public void setCrcEnabled(boolean crcEnabled) {
        this.crcEnabled = crcEnabled;
    }

    public void setGate(TransferGate gate) {
        this.gate = (gate == null) ? TransferGate.NOOP : gate;
    }

    public TransferGate getGate() {
        return gate;
    }

    public boolean isCrcEnabled() {
        return crcEnabled;
    }

    @Override
    public Void call() throws Exception {
        try {
            for (RemoteFile file : files) {
                if (!gate.awaitUnpaused()) {
                    throw new InterruptedException("transfer aborted");
                }
                if (!fileExists(file.getPath())) {
                    continue;
                }
                readToDeque(file);
                if (file.isDirectory()) {
                    listFilesAndRead(file);
                }
            }
            for (int i = 0; i < operateThreadCount; i++) {
                deque.add(END_POINT);
            }
        } catch (Exception e) {
            //当发生读取错误时
            for (int i = 0; i < operateThreadCount; i++) {
                deque.add(READ_ERROR);
            }
            throw e;
        }
        return null;
    }

    private void listFilesAndRead(RemoteFile folder) throws Exception {
        List<RemoteFile> files = listFiles(folder.getPath());
        if (files != null) {
            for (RemoteFile file : files) {
                if (!gate.awaitUnpaused()) {
                    throw new InterruptedException("transfer aborted");
                }
                readToDeque(file);
                if (file.isDirectory()) {
                    listFilesAndRead(file); // 递归遍历子文件夹
                }
            }
        }
    }

    /**
     * 按"块下标 + channel.position() 定位"切块（契约 §5.3）。
     *
     * <p>与原实现的两点区别：
     * <ol>
     *     <li>支持跳过已完成块：跳过的块既不占缓冲、也不读盘；</li>
     *     <li>修掉原实现 {@code while (buffer.hasRemaining()) channel.read(buffer);} 在读到 -1 时的
     *     死循环（position 不前进）。</li>
     * </ol>
     */
    private void readToDeque(RemoteFile file) throws Exception {
        fileIndex++;
        String destPath = localDir.generateTransferPath(file.getPath(), remoteDir);
        if (file.isDirectory()) {
            // 目录块永远重发（幂等）
            deque.add(new FileBlock(false, fileIndex, destPath, file.lastModified(), 0, 0, null));
            return;
        }
        FileChannel channel = openFile(file.getPath());
        long length = channel.size();
        long lastModified = file.lastModified();
        int blockCount = FileBlock.calcBlockCount(length);
        if (skipSet.isFileCompleted(destPath, blockCount)) {
            // 整文件已完成：不开缓冲、不读盘
            closeFile();
            return;
        }
        for (int i = 0; i < blockCount; i++) {
            if (!gate.awaitUnpaused()) {
                throw new InterruptedException("transfer aborted");
            }
            int blkSize = (int) Math.min(length - (long) i * FileBlock.BLOCK_SIZE, FileBlock.BLOCK_SIZE);
            if (blkSize < 0) {
                blkSize = 0;
            }
            if (!skipSet.isSendable(destPath, i, blockCount)) {
                // 已完成块：不占缓冲、不读盘
                continue;
            }
            ByteBuffer buffer = buffers.take();
            buffer.clear();
            buffer.limit(blkSize);
            if (blkSize > 0) {
                channel.position((long) i * FileBlock.BLOCK_SIZE);
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) == -1) {
                        throw new EOFException("unexpected EOF: " + file.getPath());
                    }
                }
            }
            deque.add(new FileBlock(true, fileIndex, destPath, lastModified, length, i, buffer));
        }
        closeFile();
    }

    public void recycleBuffer(ByteBuffer buffer) {
        buffers.add(buffer);
    }

    public FileBlock takeBlock() throws InterruptedException {
        return deque.take();
    }

    //当对方写入时发生错误时
    public void shutdownByWriteError() {
        recycleAllBuffer();
        for (int i = 0; i < operateThreadCount; i++) {
            deque.addFirst(WRITE_ERROR);
        }
    }

    //当其中任意一条通道断开时
    public void shutdownByConnectionBreak() {
        recycleAllBuffer();
        for (int i = 0; i < operateThreadCount - 1; i++) {
            deque.addFirst(INTERRUPT);
        }
    }

    private void recycleAllBuffer() {
        for (FileBlock fileBlock : deque) {
            if (fileBlock.data != null) {
                recycleBuffer(fileBlock.data);
            }
        }
    }

    protected abstract boolean fileExists(String path) throws Exception;

    protected abstract List<RemoteFile> listFiles(String path) throws Exception;

    protected abstract FileChannel openFile(String path) throws Exception;

    protected abstract void closeFile() throws Exception;

}
