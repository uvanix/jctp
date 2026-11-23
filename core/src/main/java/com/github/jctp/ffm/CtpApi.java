package com.github.jctp.ffm;

import lombok.extern.slf4j.Slf4j;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

import static com.github.jctp.ffm.CtpLibrary.*;
import static java.lang.foreign.ValueLayout.*;

/**
 * CThostFtdcMdApi / CThostFtdcTraderApi 的公共部分：对象生命周期与虚函数调用。
 * <p>
 * {@link #close()} 会调用原生 Release()，后者等待 CTP 内部线程退出，因此不能在 Spi 回调线程里关闭。
 * 请求与关闭之间用读写锁隔离；请求只 tryLock，关闭进行中时立即返回 -1，避免回调线程与 Release 互等。
 */
@Slf4j
abstract class CtpApi implements AutoCloseable {

    // Spi 回调的 C 签名，首参均为 this
    static final FunctionDescriptor ON_VOID = FunctionDescriptor.ofVoid(ADDRESS);
    static final FunctionDescriptor ON_INT = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT);
    /**
     * OnRspXxx(T *p, CThostFtdcRspInfoField *pRspInfo, int nRequestID, bool bIsLast)
     */
    static final FunctionDescriptor ON_RSP = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_BOOLEAN);
    /**
     * OnRspError(CThostFtdcRspInfoField *pRspInfo, int nRequestID, bool bIsLast)
     */
    static final FunctionDescriptor ON_ERROR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_BOOLEAN);
    /**
     * OnRtnXxx(T *p)
     */
    static final FunctionDescriptor ON_RTN = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);
    /**
     * OnErrRtnXxx(T *p, CThostFtdcRspInfoField *pRspInfo)
     */
    static final FunctionDescriptor ON_ERR_RTN = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS);


    final Arena arena = Arena.ofShared();
    final CtpVersion version;
    /**
     * 与原生库版本一致的结构体布局
     */
    final CtpStructs structs;
    /**
     * 与原生库版本一致的虚函数表，槽位按方法名查得
     */
    final CtpVtables vtables;
    private final List<String> apiTable;
    // Api 公共虚函数槽位
    private final int releaseSlot;
    private final int initSlot;
    private final int getTradingDaySlot;
    private final int registerFrontSlot;
    private final int registerSpiSlot;
    final MemorySegment self;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private boolean released;

    CtpApi(CtpLibrary library, Path flowPath, boolean trader) {
        // CTP 直接拼接流文件名，目录必须以分隔符结尾
        String path = flowPath.toAbsolutePath().toString();
        if (!path.endsWith("/") && !path.endsWith("\\")) {
            path += flowPath.getFileSystem().getSeparator();
        }
        MemorySegment flow = CtpField.cString(arena, path);
        this.version = library.version();
        this.structs = version.structs();
        this.vtables = CtpVtables.of(version);
        this.apiTable = trader ? vtables.traderApi : vtables.mdApi;
        this.releaseSlot = apiSlot("Release");
        this.initSlot = apiSlot("Init");
        this.getTradingDaySlot = apiSlot("GetTradingDay");
        this.registerFrontSlot = apiSlot("RegisterFront");
        this.registerSpiSlot = apiSlot("RegisterSpi");
        this.self = trader ? library.createTraderApi(flow) : library.createMdApi(flow);
    }

    MemorySegment rspInfo(MemorySegment pointer) {
        return struct(pointer, structs.rspInfo.SIZE);
    }

    public CtpVersion version() {
        return version;
    }

    public CtpStructs structs() {
        return structs;
    }

    public void registerFront(String address) {
        // CTP 保存的是指针，地址字符串须与 Api 同生命周期
        callVoid(registerFrontSlot, CtpField.cString(arena, address));
    }

    public void init() {
        callVoid(initSlot);
    }

    public String getTradingDay() {
        return withLock(() -> {
            try {
                return cString((MemorySegment) PTR_SELF.invokeExact(virtualFunction(self, getTradingDaySlot), self));
            } catch (Throwable e) {
                throw rethrow(e);
            }
        });
    }

    /**
     * 先注销 Spi 再 Release，随后释放 upcall stub 与常驻内存。
     */
    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            if (released) {
                return;
            }
            released = true;
            invokeVoid(registerSpiSlot, MemorySegment.NULL);
            invokeVoid(releaseSlot);
            arena.close();
        } finally {
            lock.writeLock().unlock();
        }
    }

    void registerSpi(MemorySegment spi) {
        callVoid(registerSpiSlot, spi);
    }

    /**
     * 在临时内存里填充请求结构体并发送。CTP 在 ReqXxx 返回前已完成序列化，结构体无需保活。
     *
     * @return 0 成功；-1 网络连接失败/已关闭；-2 未处理请求超过许可数；-3 每秒发送请求数超过许可数
     */
    int request(int slot, long size, Consumer<MemorySegment> filler, int requestId) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment req = temp.allocate(size);
            filler.accept(req);
            return callInt(slot, req, requestId);
        }
    }

    int callInt(int slot, MemorySegment pointer, int value) {
        if (!lock.readLock().tryLock()) {
            return -1;
        }
        try {
            if (released) {
                return -1;
            }
            return (int) INT_SELF_PTR_INT.invokeExact(virtualFunction(self, slot), self, pointer, value);
        } catch (Throwable e) {
            throw rethrow(e);
        } finally {
            lock.readLock().unlock();
        }
    }

    void callVoid(int slot, int value) {
        withLock(() -> {
            try {
                VOID_SELF_INT.invokeExact(virtualFunction(self, slot), self, value);
            } catch (Throwable e) {
                throw rethrow(e);
            }
            return null;
        });
    }

    private void callVoid(int slot) {
        withLock(() -> {
            invokeVoid(slot);
            return null;
        });
    }

    private void callVoid(int slot, MemorySegment pointer) {
        withLock(() -> {
            invokeVoid(slot, pointer);
            return null;
        });
    }

    private void invokeVoid(int slot) {
        try {
            VOID_SELF.invokeExact(virtualFunction(self, slot), self);
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    private void invokeVoid(int slot, MemorySegment pointer) {
        try {
            VOID_SELF_PTR.invokeExact(virtualFunction(self, slot), self, pointer);
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    private <T> T withLock(java.util.function.Supplier<T> action) {
        lock.readLock().lock();
        try {
            if (released) {
                throw new IllegalStateException("CTP Api 已释放");
            }
            return action.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 把子类的私有分发方法绑定为 upcall。分发方法必须自行捕获全部异常：异常穿出 upcall 会使 JVM 崩溃。
     */
    /**
     * Api 方法在当前版本虚函数表中的槽位
     */
    final int apiSlot(String method) {
        return CtpVtables.slot(apiTable, method);
    }

    /**
     * 按回调名构造 Spi 对象：槽位取自当前版本的 Spi 虚函数表，未列出的回调为空函数。
     */
    MemorySegment newSpi(List<String> spiTable, Map<String, Upcall> handlers) {
        Map<Integer, Upcall> bySlot = new HashMap<>();
        handlers.forEach((name, upcall) -> bySlot.put(CtpVtables.slot(spiTable, name), upcall));
        return CtpLibrary.newSpi(arena, spiTable.size(), bySlot);
    }

    Upcall upcall(MethodHandles.Lookup lookup, String method, FunctionDescriptor descriptor) {
        try {
            return new Upcall(lookup.findVirtual(getClass(), method, descriptor.toMethodType()).bindTo(this), descriptor);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static void guard(String callback, Runnable action) {
        try {
            action.run();
        } catch (Throwable e) {
            log.error("CTP 回调 {} 处理异常", callback, e);
        }
    }
}
