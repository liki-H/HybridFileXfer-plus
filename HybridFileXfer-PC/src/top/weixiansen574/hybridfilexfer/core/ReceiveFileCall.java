package top.weixiansen574.hybridfilexfer.core;

import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.nio.DataByteChannel;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Callable;

public class ReceiveFileCall implements Callable<Void> {
    private final WriteFileCall writeFileCall;
    private final int tIndex;
    private final DataByteChannel channel;
    private final TransferFileCallback callback;
    private final TransferConnection connection;
    private final String iName;

    public ReceiveFileCall(int tIndex, TransferConnection connection,WriteFileCall writeFileCall,TransferFileCallback callback) {
        this.writeFileCall = writeFileCall;
        this.tIndex = tIndex;
        this.connection = connection;
        this.channel = connection.channel;
        this.callback = callback;
        iName = connection.iName;
        connection.resetTotalTrafficInfo();
        //this.dis = dataInputStream;
    }

    @Override
    public Void call() throws Exception {
        long startTime = System.currentTimeMillis();
        try {
            while (true) {
                // 门控：只在"读块头前"判定，绝不打断已经开始的块
                if (!writeFileCall.getGate().awaitUnpaused()) {
                    writeFileCall.finishChannel(tIndex);
                    return null;
                }
                short header = channel.readShort();
                switch (header) {
                    case TransferIdentifiers.FOLDER: {
                        int fileIndex = channel.readInt();
                        String path = channel.readUTF();
                        long lastModified = channel.readLong();
                        writeFileCall.putBlock(new FileBlock(false, fileIndex, path, lastModified, 0, 0, null), tIndex);
                        break;
                    }
                    case TransferIdentifiers.FILE: {
                        int fileIndex = channel.readInt();
                        String path = channel.readUTF();
                        long lastModified = channel.readLong();
                        long totalSize = channel.readLong();
                        int index = channel.readInt();
                        int length = channel.readInt();
                        boolean checkCrc = writeFileCall.isCrcEnabled();
                        int expectedCrc = 0;
                        if (checkCrc) {
                            // crc32 紧跟 int length、data 之前（契约 §4.2）
                            expectedCrc = channel.readInt();
                        }
                        callback.onFileDownloading(iName, path,
                                index * (long) FileBlock.BLOCK_SIZE + length,
                                totalSize);
                        ByteBuffer buffer = writeFileCall.getBuffer();
                        buffer.clear();
                        buffer.limit(length);
                        int read;
                        while (buffer.hasRemaining()) {
                            read = channel.read(buffer);
                            if (read == -1) {
                                throw new EOFException();
                            }
                            connection.addDownloadedBytes(read);
                        }
                        channel.readFully(buffer);
                        // index 越界校验（数据已读完，不会让 wire 残留半个块）
                        int blockCount = FileBlock.calcBlockCount(totalSize);
                        if (index < 0 || index >= blockCount) {
                            throw new IOException("bad block index " + index + " for " + path
                                    + " (blockCount=" + blockCount + ")");
                        }
                        if (checkCrc) {
                            // 校验放在 putBlock 之前：不写盘 + 整文件标记未完成 + 中止
                            int actualCrc = Crc32.compute(buffer, 0, length);
                            if (actualCrc != expectedCrc) {
                                writeFileCall.abortResumeFile(path);
                                throw new IOException("CRC32 mismatch: " + path + " block=" + index
                                        + " expected=0x" + Integer.toHexString(expectedCrc)
                                        + " actual=0x" + Integer.toHexString(actualCrc));
                            }
                        }
                        writeFileCall.putBlock(new FileBlock(true, fileIndex, path, lastModified, totalSize, index, buffer), tIndex);
                        break;
                    }
                    case TransferIdentifiers.EOF:
                        //System.out.println(iName + " 接收完成");
                        writeFileCall.finishChannel(tIndex);
                        callback.onChannelComplete(iName,
                                connection.getTotalTraffic().downloadTraffic,
                                System.currentTimeMillis() - startTime);
                        return null;
                    case TransferIdentifiers.END_OF_INTERRUPTED:
                        //System.out.println("传输通道：" + iName + " 已中断，因其他通道断开");
                        writeFileCall.cancel();
                        callback.onChannelError(iName,
                                TransferFileCallback.ERROR_TYPE_INTERRUPT,null);
                        return null;
                    case TransferIdentifiers.END_OF_READ_ERROR:
                        //System.out.println("传输通道：" + iName + " 已取消传输，因为读取文件时发生异常");
                        writeFileCall.cancel();
                        callback.onChannelError(iName,
                                TransferFileCallback.ERROR_TYPE_READ_ERROR,null);
                        return null;
                    case TransferIdentifiers.END_OF_WRITE_ERROR:
                        //System.out.println("传输通道：" + iName + " 已取消传输，因为写入文件时发生异常");
                        writeFileCall.cancel();
                        callback.onChannelError(iName,
                                TransferFileCallback.ERROR_TYPE_WRITE_ERROR,null);
                        return null;
                }
            }
        } catch (IOException e){
            writeFileCall.finishChannel(tIndex);
            callback.onChannelError(iName,
                    TransferFileCallback.ERROR_TYPE_EXCEPTION,e.toString());
            throw e;
        }
    }
}
