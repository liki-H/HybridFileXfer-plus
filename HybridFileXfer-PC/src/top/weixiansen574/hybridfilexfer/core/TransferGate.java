package top.weixiansen574.hybridfilexfer.core;

/**
 * 传输门控：暂停 / 恢复 / 中止（契约 §6.1）。
 *
 * <p>暂停只做本地门控，不占协议、不拆 socket；门控点只在"块边界"（见 {@link ReadFileCall}、
 * {@link SendFileCall}、{@link ReceiveFileCall}、{@link WriteFileCall}）。
 *
 * <p>{@link #abort()} 的存在解决一个真实死锁：暂停期间若对端断线/写端报错/用户退出，
 * 阻塞在 {@link #awaitUnpaused()} 的线程必须能被唤醒。
 */
public class TransferGate {

    /** 永不暂停的门控（未注入时的默认值） */
    public static final TransferGate NOOP = new TransferGate() {
        @Override
        public void pause() {
        }

        @Override
        public void resume() {
        }

        @Override
        public void abort() {
        }

        @Override
        public boolean awaitUnpaused() {
            return true;
        }

        @Override
        public boolean isPaused() {
            return false;
        }

        @Override
        public boolean isAborted() {
            return false;
        }
    };

    private final Object lock = new Object();
    private boolean paused;
    private boolean aborted;

    public void pause() {
        synchronized (lock) {
            if (!aborted) {
                paused = true;
            }
        }
    }

    public void resume() {
        synchronized (lock) {
            paused = false;
            lock.notifyAll();
        }
    }

    /** 会话结束/异常/取消：所有等待者立即返回 false。幂等、不可逆。 */
    public void abort() {
        synchronized (lock) {
            aborted = true;
            paused = false;
            lock.notifyAll();
        }
    }

    public boolean isPaused() {
        synchronized (lock) {
            return paused;
        }
    }

    public boolean isAborted() {
        synchronized (lock) {
            return aborted;
        }
    }

    /**
     * 阻塞直到不再处于暂停态。
     *
     * @return true = 继续；false = 会话已中止，调用方走自己的结束分支
     */
    public boolean awaitUnpaused() throws InterruptedException {
        synchronized (lock) {
            while (paused && !aborted) {
                lock.wait();
            }
            return !aborted;
        }
    }
}
