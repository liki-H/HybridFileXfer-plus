package top.weixiansen574.hybridfilexfer.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;

public abstract class WriteFileCall implements Callable<Void> {
    private final LinkedBlockingDeque<ByteBuffer> buffers;
    private final boolean[] channelFinished;
    private final ArrayList<LinkedList<FileBlock>> dequeArray;
    private boolean canceled = false;

    /** 续传清单会话（可为 null = 无续传） */
    private ResumeSession resumeSession;
    /** 是否每块校验 CRC32（发送方决定，接收方遵从） */
    private boolean crcEnabled = false;
    /** 门控（暂停/恢复） */
    private TransferGate gate = TransferGate.NOOP;
    /** 自上次落盘以来写成功的块数 */
    private int writtenSinceFlush;
    /** 当前打开的写入通道（用于 force） */
    private FileChannel activeChannel;

    public WriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount) {
        this.buffers = buffers;
        dequeArray = new ArrayList<>(dequeCount);
        channelFinished = new boolean[dequeCount];  // 初始化通道结束状态
        for (int i = 0; i < dequeCount; i++) {
            dequeArray.add(new LinkedList<>());
        }
    }

    public void setGate(TransferGate gate) {
        this.gate = (gate == null) ? TransferGate.NOOP : gate;
    }

    public TransferGate getGate() {
        return gate;
    }

    public void setResumeSession(ResumeSession resumeSession) {
        this.resumeSession = resumeSession;
    }

    public ResumeSession getResumeSession() {
        return resumeSession;
    }

    public void setCrcEnabled(boolean crcEnabled) {
        this.crcEnabled = crcEnabled;
    }

    public boolean isCrcEnabled() {
        return crcEnabled;
    }

    @Override
    public Void call() throws Exception {
        try {
            FileBlock block = takeBlock();
            FileBlock lastBlock = null;
            /*File lastFile = null;
            RandomAccessFile lastRaf = null;*/
            FileChannel lastChannel = null;
            long cursor = 0;

            while (block != null) {
                if (block.isDirectory()) {
                    //File file = new File(block.path);
                    String file = block.path;
                    tryMkdirs(file);
                    setLastModified(file, block.lastModified);
                } else {
                    //创建文件的父目录，如果不存在，保证后续文件能够创建
                    createParentDirIfNotExists(block.path);
                    //RandomAccessFile raf;
                    FileChannel channel;
                    //如果上个文件与当前
                    if (lastBlock == null || !lastBlock.path.equals(block.path)) {
                        if (lastChannel != null) {
                            // 文件收尾：先 force 数据 → 关 fd → 修 mtime → 再落清单
                            forceCurrentChannel();
                            closeFile();
                            activeChannel = null;
                            setLastModified(lastBlock.path, lastBlock.lastModified);
                            if (resumeSession != null) {
                                resumeSession.flush();
                            }
                            writtenSinceFlush = 0;
                        }
                        /*raf = new RandomAccessFile(file, "rw");
                        raf.setLength(block.totalSize);
                        channel = raf.getChannel();*/
                        channel = createAndOpenFile(block.path, block.totalSize);
                        activeChannel = channel;
                        cursor = 0;
                    } else {
                        //raf = lastRaf;
                        channel = lastChannel;
                    }
                    //如果上个指针与当前指针不不一致就进行seek操作
                    if (cursor != block.getStartPosition()) {
                        cursor = block.getStartPosition();
                        channel.position(cursor);
                    }
                     /*   logSeek(block);
                    } else {
                        logBlock(block);
                    }*/

                    ByteBuffer data = block.data;
                    data.flip();
                    channel.write(data);
                    cursor += data.position();
                    //回收缓冲区块
                    buffers.add(block.data);

                    if (resumeSession != null) {
                        int blockCount = FileBlock.calcBlockCount(block.totalSize);
                        if (block.index < 0 || block.index >= blockCount) {
                            throw new IOException("bad block index " + block.index + " for " + block.path
                                    + " (blockCount=" + blockCount + ")");
                        }
                        resumeSession.onBlockWritten(block.path, block.totalSize,
                                block.lastModified, block.index, blockCount);
                        if (++writtenSinceFlush >= ResumeSession.FLUSH_BLOCK_INTERVAL) {
                            forceCurrentChannel();
                            resumeSession.flush();
                            writtenSinceFlush = 0;
                        }
                    }
                    /*lastFile = file;
                    lastRaf = raf;*/
                    lastBlock = block;
                    lastChannel = channel;
                }
                // 门控：只在块边界判定（每块一次）；返回 false 表示会话已中止
                if (!gate.awaitUnpaused()) {
                    cancel();
                    break;
                }
                block = takeBlock();
            }
            if (lastBlock != null) {
                forceCurrentChannel();
                closeFile();
                activeChannel = null;
                setLastModified(lastBlock.path, lastBlock.lastModified);
                if (resumeSession != null) {
                    resumeSession.flush();
                }
                writtenSinceFlush = 0;
            }
        } catch (IOException e){
            cancel();
            throw e;
        }
        return null;
    }

    /** force 当前写入通道（失败忽略：清单只记录"已写盘"的块，宁少不多） */
    private void forceCurrentChannel() {
        FileChannel channel = activeChannel;
        if (channel == null || !channel.isOpen()) {
            return;
        }
        try {
            channel.force(false);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 供 ReceiveFileCall 的 CRC 失败调用：整文件标记未完成（清空该文件所有位 + 立即落盘） */
    public final void abortResumeFile(String path) {
        ResumeSession session = resumeSession;
        if (session != null) {
            session.abortFile(path);
        }
    }

    /** 供协商阶段修 mtime（包了 protected setFileLastModified） */
    public final void touchFile(String path, long lastModified) {
        try {
            setLastModified(path, lastModified);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /** 目标文件当前长度；不存在/出错返回 -1 */
    public final long queryFileLength(String path) {
        try {
            return lengthOfFile(path);
        } catch (Throwable th) {
            return -1L;
        }
    }

    private void logSeek(FileBlock block) {
        System.out.printf("seek: %d %s %d %d %d%n",
                block.getStartPosition(), block.path, block.totalSize, block.index, block.getLength());
    }

    private void logBlock(FileBlock block) {
        System.out.printf("%s %d %d %d%n",
                block.path, block.totalSize, block.index, block.getLength());
    }

    public ByteBuffer getBuffer() throws InterruptedException {
        return buffers.take();
    }

    // 新增方法：标记通道结束
    public synchronized void finishChannel(int tIndex) {
        channelFinished[tIndex] = true;
        notify();  // 唤醒可能阻塞的写线程
    }

    public synchronized void cancel(){
        canceled = true;
        //回收未写入硬盘的块的ByteBuffer
        for (LinkedList<FileBlock> deque : dequeArray) {
            for (FileBlock fileBlock : deque) {
                if (fileBlock.data != null){
                    buffers.add(fileBlock.data);
                }
            }
        }
        notify();
    }

    // 修改后的putBlock（保持原有逻辑）
    public synchronized void putBlock(FileBlock block, int tIndex) {
        //System.out.println("put:"+block.index+" "+tIndex);
        dequeArray.get(tIndex).add(block);
        notify();  // 唤醒可能阻塞的写线程
    }


    // 重构后的takeBlock（实现阻塞等待）
    private synchronized FileBlock takeBlock() throws InterruptedException {
        while (true) {
            FileBlock block = tryTakeBlockInternal();

            if (block != null) return block;

            //检查终止条件：所有通道结束 + 所有队列为空
            if (canceled || (allChannelsFinished() && allQueuesEmpty())) {
                return null;
            }

            wait();  // 阻塞等待直到被唤醒
        }
    }

    public synchronized FileBlock tryTakeBlockInternal() {
        FileBlock minHead = null;
        int mdqIndex = -1;

        for (int i = 0; i < dequeArray.size(); i++) {
            LinkedList<FileBlock> deque = dequeArray.get(i);
            if (!deque.isEmpty()) {
                FileBlock head = deque.getFirst();
                if (minHead == null || head.compareTo(minHead) < 0) {
                    minHead = head;
                    mdqIndex = i;
                }
            }
        }
        if (minHead != null) {
            dequeArray.get(mdqIndex).removeFirst();
        }
        return minHead;
    }

    private boolean allChannelsFinished() {
        for (boolean finished : channelFinished) {
            if (!finished) return false;
        }
        return true;
    }

    // 辅助方法：检查所有队列是否为空
    private boolean allQueuesEmpty() {
        for (LinkedList<FileBlock> deque : dequeArray) {
            if (!deque.isEmpty()) return false;
        }
        return true;
    }

    private void setLastModified(String file, long time) throws Exception {
        if (!setFileLastModified(file,time)) {
            System.out.println("Warning! file cannot set last modified:" + file);
        }
    }

    protected abstract void createParentDirIfNotExists(String path) throws Exception;
    protected abstract void tryMkdirs(String path) throws Exception;
    protected abstract FileChannel createAndOpenFile(String path,long length) throws Exception;
    protected abstract void closeFile() throws Exception;
    protected abstract boolean setFileLastModified(String path,long time) throws Exception;
    /** 目标文件长度（用于协商阶段的长度校验）；不存在返回 -1 */
    protected abstract long lengthOfFile(String path) throws Exception;
}
