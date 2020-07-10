package kcp;

import com.backblaze.erasure.ReedSolomon;
import com.backblaze.erasure.fec.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.shaded.org.jctools.queues.MpscChunkedArrayQueue;
import org.jctools.queues.MpscLinkedQueue;
import threadPool.thread.IMessageExecutor;

import java.io.IOException;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

public class Ukcp{

    private static final InternalLogger log = InternalLoggerFactory.getInstance(Ukcp.class);


    private final Kcp kcp;

    private boolean fastFlush = true;

    private long tsUpdate = -1;

    private boolean active;

    private FecEncode fecEncode = null;
    private FecDecode fecDecode = null;

    private final Queue<ByteBuf> writeQueue;

    private final Queue<ByteBuf> readQueue;

    private final IMessageExecutor iMessageExecutor;

    private final KcpListener kcpListener;

    private final long timeoutMillis;

    private final IChannelManager channelManager;

    private AtomicBoolean writeProcessing = new AtomicBoolean(false);

    private AtomicBoolean readProcessing = new AtomicBoolean(false);

    /**
     * 上次收到消息时间
     **/
    private long lastRecieveTime = System.currentTimeMillis();


    /**
     * Creates a new instance.
     *
     * @param output output for kcp
     */
    public Ukcp(KcpOutput output, KcpListener kcpListener, IMessageExecutor iMessageExecutor,ReedSolomon reedSolomon,ChannelConfig channelConfig,IChannelManager channelManager) {
        this.timeoutMillis = channelConfig.getTimeoutMillis();
        this.kcp = new Kcp(channelConfig.getConv(), output);
        this.active = true;
        this.kcpListener = kcpListener;
        this.iMessageExecutor = iMessageExecutor;
        this.channelManager = channelManager;
        //默认2<<16   可以修改
        writeQueue = new MpscLinkedQueue<>();
        readQueue = new MpscChunkedArrayQueue<>(2<<11);

        int headerSize = 0;
        //init fec
        if (reedSolomon != null) {
            KcpOutput kcpOutput = kcp.getOutput();
            fecEncode = new FecEncode(headerSize, reedSolomon,channelConfig.getMtu());
            fecDecode = new FecDecode(3 * reedSolomon.getTotalShardCount(), reedSolomon,channelConfig.getMtu());
            kcpOutput = new FecOutPut(kcpOutput, fecEncode);
            kcp.setOutput(kcpOutput);
            headerSize+= Fec.fecHeaderSizePlus2;
        }

        kcp.setReserved(headerSize);
        initKcpConfig(channelConfig);
    }


    private void initKcpConfig(ChannelConfig channelConfig){
        kcp.nodelay(channelConfig.isNodelay(),channelConfig.getInterval(),channelConfig.getFastresend(),channelConfig.isNocwnd());
        kcp.setSndWnd(channelConfig.getSndwnd());
        kcp.setRcvWnd(channelConfig.getRcvwnd());
        kcp.setMtu(channelConfig.getMtu());
        kcp.setStream(channelConfig.isStream());
        kcp.setAckNoDelay(channelConfig.isAckNoDelay());
        kcp.setAckMaskSize(channelConfig.getAckMaskSize());
        this.fastFlush = channelConfig.isFastFlush();
    }


    /**
     * Receives ByteBufs.
     *
     * @param bufList received ByteBuf will be add to the list
     */
    protected void receive(List<ByteBuf> bufList) {
        kcp.recv(bufList);
    }


    protected ByteBuf mergeReceive() {
        return kcp.mergeRecv();
    }


    protected void input(ByteBuf data,long current) throws IOException {
        //lastRecieveTime = System.currentTimeMillis();
        Snmp.snmp.InPkts.increment();
        Snmp.snmp.InBytes.add(data.readableBytes());

        if (fecDecode != null) {
            FecPacket fecPacket = FecPacket.newFecPacket(data);
            if (fecPacket.getFlag() == Fec.typeData) {
                data.skipBytes(2);
                input(data, true,current);
            }
            if (fecPacket.getFlag() == Fec.typeData || fecPacket.getFlag() == Fec.typeParity) {
                List<ByteBuf> byteBufs = fecDecode.decode(fecPacket);
                if (byteBufs != null) {
                    for (ByteBuf byteBuf : byteBufs) {
                        input(byteBuf, false,current);
                        byteBuf.release();
                    }
                }
            }
        } else {
            input(data, true,current);
        }
    }

    private void input(ByteBuf data, boolean regular,long current) throws IOException {
        int ret = kcp.input(data, regular,current);
        switch (ret) {
            case -1:
                throw new IOException("No enough bytes of head");
            case -2:
                throw new IOException("No enough bytes of data");
            case -3:
                throw new IOException("Mismatch cmd");
            case -4:
                throw new IOException("Conv inconsistency");
            default:
                break;
        }
    }


    /**
     * Sends a Bytebuf.
     *
     * @param buf
     * @throws IOException
     */
    void send(ByteBuf buf) throws IOException {
        int ret = kcp.send(buf);
        switch (ret) {
            case -2:
                throw new IOException("Too many fragments");
            default:
                break;
        }
    }


    /**
     * Returns {@code true} if there are bytes can be received.
     *
     * @return
     */
    protected boolean canRecv() {
        return kcp.canRecv();
    }



    protected long getLastRecieveTime() {
        return lastRecieveTime;
    }

    protected void setLastRecieveTime(long lastRecieveTime) {
        this.lastRecieveTime = lastRecieveTime;
    }

    /**
     * Returns {@code true} if the kcp can send more bytes.
     *
     * @param curCanSend last state of canSend
     * @return {@code true} if the kcp can send more bytes
     */
    protected boolean canSend(boolean curCanSend) {
        int max = kcp.getSndWnd() * 2;
        int waitSnd = kcp.waitSnd();
        if (curCanSend) {
            return waitSnd < max;
        } else {
            int threshold = Math.max(1, max / 2);
            return waitSnd < threshold;
        }
    }

    /**
     * Udpates the kcp.
     *
     * @param current current time in milliseconds
     * @return the next time to update
     */
    protected long update(long current) {
        kcp.update(current);
        long nextTsUp = check(current);
        setTsUpdate(nextTsUp);

        return nextTsUp;
    }

    protected long flush(long current){
        return kcp.flush(false,current);
    }

    /**
     * Determines when should you invoke udpate.
     *
     * @param current current time in milliseconds
     * @return
     * @see Kcp#check(long)
     */
    protected long check(long current) {
        return kcp.check(current);
    }

    /**
     * Returns {@code true} if the kcp need to flush.
     *
     * @return {@code true} if the kcp need to flush
     */
    protected boolean checkFlush() {
        return kcp.checkFlush();
    }

    /**
     * Returns conv of kcp.
     *
     * @return conv of kcp
     */
    public int getConv() {
        return kcp.getConv();
    }

    /**
     * Set the conv of kcp.
     *
     * @param conv the conv of kcp
     */
    public void setConv(int conv) {
        kcp.setConv(conv);
    }


    /**
     * Returns update interval.
     *
     * @return update interval
     */
    protected int getInterval() {
        return kcp.getInterval();
    }



    protected boolean isStream() {
        return kcp.isStream();
    }



    /**
     * Sets the {@link ByteBufAllocator} which is used for the kcp to allocate buffers.
     *
     * @param allocator the allocator is used for the kcp to allocate buffers
     * @return this object
     */
    public Ukcp setByteBufAllocator(ByteBufAllocator allocator) {
        kcp.setByteBufAllocator(allocator);
        return this;
    }

    protected boolean isFastFlush() {
        return fastFlush;
    }



    protected void read(ByteBuf byteBuf) {
        if(this.readQueue.offer(byteBuf)){
            notifyReadEvent();
        }else{
            byteBuf.release();
            log.error("conv "+kcp.getConv()+" recieveList is full");
        }
    }

    /**
     * 发送有序可靠消息
     * 线程安全的
     * @param byteBuf 发送后需要手动释放
     * @return
     */
    public void write(ByteBuf byteBuf) {
        byteBuf = byteBuf.retainedDuplicate();
        if (!writeQueue.offer(byteBuf)) {
            log.error("conv "+kcp.getConv()+" sendList is full");
            byteBuf.release();
            close();
        }
        notifyWriteEvent();
    }





    /**
     * 主动关闭连接调用
     */
    public void close() {
        this.iMessageExecutor.execute(() -> internalClose());
    }

    private void notifyReadEvent() {
        if(readProcessing.compareAndSet(false,true)){
            ReadTask readTask = ReadTask.New(this);
            this.iMessageExecutor.execute(readTask);
        }
    }

    protected void notifyWriteEvent() {
        if(writeProcessing.compareAndSet(false,true)){
            WriteTask writeTask = WriteTask.New(this);
            this.iMessageExecutor.execute(writeTask);
        }
    }


    protected long getTsUpdate() {
        return tsUpdate;
    }

    protected Queue<ByteBuf> getReadQueue() {
        return readQueue;
    }

    protected Ukcp setTsUpdate(long tsUpdate) {
        this.tsUpdate = tsUpdate;
        return this;
    }

    protected Queue<ByteBuf> getWriteQueue() {
        return writeQueue;
    }

    protected KcpListener getKcpListener() {
        return kcpListener;
    }

    public boolean isActive() {
        return active;
    }


    void internalClose() {
        if(!active){
            return;
        }
        this.active = false;
        notifyReadEvent();
        kcpListener.handleClose(this);
        //关闭之前尽量把消息都发出去
        notifyWriteEvent();
        kcp.flush(false,System.currentTimeMillis());
        //连接删除
        channelManager.del(this);
        release();
    }

    void release() {
        kcp.setState(-1);
        kcp.release();
        for (; ; ) {
            ByteBuf byteBuf = writeQueue.poll();
            if (byteBuf == null) {
                break;
            }
            byteBuf.release();
        }
        for (; ; ) {
            ByteBuf byteBuf = readQueue.poll();
            if (byteBuf == null) {
                break;
            }
            byteBuf.release();
        }
        if (this.fecEncode != null) {
            this.fecEncode.release();
        }

        if (this.fecDecode != null) {
            this.fecDecode.release();
        }
    }

    protected AtomicBoolean getWriteProcessing() {
        return writeProcessing;
    }


    protected AtomicBoolean getReadProcessing() {
        return readProcessing;
    }

    protected IMessageExecutor getiMessageExecutor() {
        return iMessageExecutor;
    }

    protected long getTimeoutMillis() {
        return timeoutMillis;
    }

    @SuppressWarnings("unchecked")
    public User user() {
        return (User) kcp.getUser();
    }

    public Ukcp user(User user) {
        kcp.setUser(user);
        return this;
    }

    @Override
    public String toString() {
        return "Ukcp(" +
                "getConv=" + kcp.getConv() +
                ", state=" + kcp.getState() +
                ", active=" + active +
                ')';
    }
}
