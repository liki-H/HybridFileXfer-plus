package top.weixiansen574.hybridfilexfer.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.nio.DataByteChannel;

public abstract class HFXService {
    public static final String CLIENT_HEADER = "HFXC";
    /** 301 = block 级续传协议；与 300 不互通（旧版本会被版本校验直接拒绝） */
    public static final int VERSION_CODE = 301;
    protected final LinkedBlockingDeque<ByteBuffer> buffers = new LinkedBlockingDeque<>();
    protected DataByteChannel ctChannel;
    protected List<TransferConnection> connections;

    /** 本次会话是否每 block 带 CRC32（发送方决定，接收方无条件遵从；默认关） */
    protected volatile boolean blockCrcEnabled = false;
    /** 当前传输会话的门控；null 表示当前无传输 */
    private volatile TransferGate currentGate;

    public void setBlockCrcEnabled(boolean enabled) {
        this.blockCrcEnabled = enabled;
    }

    public boolean isBlockCrcEnabled() {
        return blockCrcEnabled;
    }

    public TransferGate getCurrentGate() {
        return currentGate;
    }

    /**
     * 强制唤醒当前门控（会话结束/异常/取消时调用）。
     *
     * <p>必须由"会让 socket 不在预期内关闭"的路径调用（{@link HFXClient#handleShutdown()}、
     * droidcore/HFXServer#disconnect()、droidcore/HFXServer#closeServerSocket()），
     * 否则暂停中的传输线程会永久阻塞在 {@link TransferGate#awaitUnpaused()}。
     */
    public final void abortTransfer() {
        TransferGate gate = currentGate;
        if (gate != null) {
            gate.abort();
        }
    }

    protected boolean sendFiles(List<RemoteFile> fileList, Directory localDir, Directory remoteDir, TransferFileCallback callback) throws IOException {
        TransferGate gate = new TransferGate();
        currentGate = gate;
        try {
            // ===== 续传协商：协商永远由发送方先发起（契约 §2.3）=====
            List<ResumeTaskKey.Entry> taskEntries = ResumeTaskKey.fromSelection(fileList, localDir, remoteDir);
            String taskKey = ResumeTaskKey.compute(taskEntries);
            ResumeProtocol.writeQuery(ctChannel, taskKey, blockCrcEnabled,
                    localDir.path, remoteDir.path, taskEntries);
            Map<String, BlockBitmap> completedBlocks = ResumeProtocol.readReply(ctChannel);

            ReadFileCall readFileCall = createReadFileCall(buffers, fileList, localDir, remoteDir, connections.size());
            readFileCall.setGate(gate);
            readFileCall.setSkipSet(completedBlocks.isEmpty()
                    ? ResumeSkipSet.EMPTY : new ResumeSkipSet(completedBlocks));
            readFileCall.setCrcEnabled(blockCrcEnabled);

            FutureTask<Void> readFileTask = new FutureTask<>(readFileCall);
            Thread readThread = new Thread(readFileTask);
            readThread.setName("FileRead");
            readThread.start();
            //另开一个线程读取传输流量信息，1秒一次
            SpeedMonitorThread speedMonitorThread = new SpeedMonitorThread(connections, callback);
            speedMonitorThread.setName("SpeedMonitor");
            speedMonitorThread.start();
            long startTime = System.currentTimeMillis();
            List<FutureTask<Void>> transferTasks = new ArrayList<>(connections.size());
            for (TransferConnection connection : connections) {
                FutureTask<Void> task = new FutureTask<>(new SendFileCall(readFileCall, connection, callback));
                transferTasks.add(task);
                Thread thread = new Thread(task);
                thread.setName("UL_" + connection.iName);
                thread.start();
            }

            //其中一条通道断掉，可能控制器通道也一起跟着断了
            boolean complete;
            try {
                //等待客户端接收成功或者写入到硬盘时发生IO错误
                complete = ctChannel.readBoolean();
            } catch (IOException e) {
                speedMonitorThread.cancel();
                callback.onIncomplete();
                return false;
            }
            speedMonitorThread.cancel();

            if (!complete) {
                String errMsg = ctChannel.readUTF();
                callback.onWriteFileError(errMsg);
                readFileCall.shutdownByWriteError();
                return true;
            }

            for (FutureTask<Void> transferTask : transferTasks) {
                try {
                    transferTask.get();
                } catch (ExecutionException | InterruptedException e) {
                    callback.onIncomplete();
                    return false;
                }
            }

            long totalUploadTraffic = 0;
            for (TransferConnection connection : connections) {
                totalUploadTraffic += connection.resetTotalTrafficInfo().uploadTraffic;
            }

            try {
                readFileTask.get();
                ctChannel.writeBoolean(true);
            } catch (ExecutionException | InterruptedException e) {
                Throwable cause = e.getCause();
                String ex = cause != null ? cause.toString() : e.toString();
                ctChannel.writeBoolean(false);
                ctChannel.writeUTF(ex);
                callback.onReadFileError(ex);
                return true;
            }

            callback.onComplete(true, totalUploadTraffic, System.currentTimeMillis() - startTime);
            return true;
        } finally {
            gate.abort();
            if (currentGate == gate) {
                currentGate = null;
            }
        }
    }

    protected boolean receiveFiles(TransferFileCallback callback) throws IOException {
        TransferGate gate = new TransferGate();
        currentGate = gate;
        ResumeSession resumeSession = null;
        boolean sessionCompleted = false;
        try {
            // ===== 续传协商（接收方被动应答；用自己算的键查清单，不信任对端值）=====
            ResumeProtocol.ResumeQuery query = ResumeProtocol.readQuery(ctChannel);
            String localTaskKey = ResumeTaskKey.compute(query.entries);
            resumeSession = new ResumeSession(localTaskKey, getResumeStore());
            resumeSession.setRemoteRoot(query.senderRoot);
            resumeSession.purgeExpiredIfNeeded(System.currentTimeMillis(), ResumeSession.RETENTION_MS);

            WriteFileCall writeFileCall = createWriteFileCall(buffers, connections.size());
            writeFileCall.setGate(gate);
            writeFileCall.setResumeSession(resumeSession);
            writeFileCall.setCrcEnabled(query.crcEnabled);

            //生成回执：只回"存在且长度一致且至少 1 位完成"的文件
            List<ResumeEntry> reply = buildResumeReply(writeFileCall, resumeSession);
            ResumeProtocol.writeReply(ctChannel, reply);

            long startTime = System.currentTimeMillis();

            SpeedMonitorThread speedMonitorThread = new SpeedMonitorThread(connections, callback);
            speedMonitorThread.setName("SpeedMonitor");
            speedMonitorThread.start();

            List<FutureTask<Void>> transferTasks = new ArrayList<>(connections.size());
            for (int i = 0; i < connections.size(); i++) {
                TransferConnection connection = connections.get(i);
                FutureTask<Void> task = new FutureTask<>(new ReceiveFileCall(i, connection, writeFileCall, callback));
                transferTasks.add(task);
                Thread thread = new Thread(task);
                thread.setName("DL_" + connection.iName);
                thread.start();
            }
            FutureTask<Void> writeFileTask = new FutureTask<>(writeFileCall);
            Thread thread = new Thread(writeFileTask);
            thread.setName("FileWrite");
            thread.start();
            try {
                writeFileTask.get();
            } catch (InterruptedException | ExecutionException e) {
                speedMonitorThread.cancel();
                Throwable cause = e.getCause();
                ctChannel.writeBoolean(false);
                String ex = cause != null ? cause.toString() : e.toString();
                ctChannel.writeUTF(ex);
                callback.onWriteFileError(ex);
                return true;
            }

            for (FutureTask<Void> task : transferTasks) {
                try {
                    task.get();
                } catch (InterruptedException | ExecutionException e) {
                    speedMonitorThread.cancel();
                    //此时没有连同控制器通道一起断掉，要通知对方，写线程没问题（对方的传输线程通道已出问题）
                    ctChannel.writeBoolean(true);
                    callback.onIncomplete();
                    return false;
                }
            }
            speedMonitorThread.cancel();
            ctChannel.writeBoolean(true);
            if (ctChannel.readBoolean()) {
                long totalDownloadTraffic = 0;
                for (TransferConnection connection : connections) {
                    totalDownloadTraffic += connection.resetTotalTrafficInfo().downloadTraffic;
                }
                //会话成功完成：删除整个任务的清单（成功即删）
                sessionCompleted = true;
                callback.onComplete(false, totalDownloadTraffic, System.currentTimeMillis() - startTime);
            } else {
                callback.onReadFileError(ctChannel.readUTF());
            }
            return true;
        } finally {
            if (resumeSession != null) {
                if (sessionCompleted) {
                    resumeSession.markTaskCompleted();
                } else {
                    //会话异常中止：尽力保存已完成块
                    resumeSession.flush();
                }
            }
            gate.abort();
            if (currentGate == gate) {
                currentGate = null;
            }
        }
    }

    /**
     * 生成续传回执（契约 §5.2）。
     *
     * <p>长度校验是必需的：否则"用户手工删掉了半成品文件"会被误判为可续传，
     * 最终产出一个前半截全零的坏文件。
     */
    private List<ResumeEntry> buildResumeReply(WriteFileCall writeFileCall, ResumeSession resumeSession) {
        List<ResumeEntry> reply = new ArrayList<>();
        if (resumeSession == null) {
            return reply;
        }
        List<ResumeEntry> existing = resumeSession.loadExisting();
        for (int i = 0; i < existing.size(); i++) {
            ResumeEntry entry = existing.get(i);
            long length;
            try {
                length = writeFileCall.queryFileLength(entry.localPath);
            } catch (Throwable th) {
                length = -1L;
            }
            if (length != entry.totalSize) {
                //目标被删/被改/长度不符 → 清位 + 删行 + 不回执（发送端整文件重传）
                resumeSession.abortFile(entry.localPath);
                continue;
            }
            if (!entry.bitmap.hasCompleted()) {
                //没有已完成块，不必回执（省流量）
                continue;
            }
            if (entry.bitmap.isAllCompleted()) {
                //整文件已完成：修 mtime
                try {
                    writeFileCall.touchFile(entry.localPath, entry.lastModified);
                } catch (Throwable th) {
                    th.printStackTrace();
                }
            }
            reply.add(entry);
        }
        return reply;
    }

    protected abstract WriteFileCall createWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount);

    protected abstract ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files, Directory localDir, Directory remoteDir, int operateThreadCount);

    /** 续传清单存储（接收方才有意义）；返回 null 表示本端不支持续传（降级为全量传输） */
    protected abstract ResumeStore getResumeStore();

}
