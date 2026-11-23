package com.github.jctp.ffm;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.ValueLayout.*;

/**
 * 加载 CTP 原生库（thostmduserapi_se / thosttraderapi_se），并提供 C++ 虚函数调用与 SPI 回调对象构造。
 * <p>
 * CTP 的 Api/Spi 都是只有虚函数、没有数据成员的 C++ 类：对象首 8 字节是 vtable 指针，
 * 成员函数按声明顺序占用 vtable 槽位；x64 下 MSVC 与 Itanium ABI 调用成员函数都等价于把 this 作为首个参数的 C 函数。
 * 因此 Java 侧只需：
 * <ul>
 *     <li>调用 Api：读 vtable[slot] 拿到函数地址，按 (this, args...) 发起 downcall；</li>
 *     <li>实现 Spi：分配一张由 upcall stub 组成的 vtable，再分配一个首字段指向它的 8 字节“对象”交给 RegisterSpi。</li>
 * </ul>
 * Spi 基类没有虚析构函数，vtable 只含回调方法本身。
 * <p>
 * 每个 {@link CtpVersion} 各加载一份原生库，按完整路径加载、互不干扰，同一进程内可同时使用多个版本。
 */
@Slf4j
public final class CtpLibrary {

    static final Linker LINKER = Linker.nativeLinker();

    // 虚函数调用句柄：第一个参数为函数地址，第二个为 this
    static final MethodHandle VOID_SELF = LINKER.downcallHandle(FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle INT_SELF = LINKER.downcallHandle(FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle PTR_SELF = LINKER.downcallHandle(FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle VOID_SELF_PTR = LINKER.downcallHandle(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    static final MethodHandle VOID_SELF_INT = LINKER.downcallHandle(FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    /**
     * int Xxx(void *this, T *p, int n)：ReqXxx(pField, nRequestID) 与 SubscribeMarketData(ppInstrumentID, nCount) 同形
     */
    static final MethodHandle INT_SELF_PTR_INT = LINKER.downcallHandle(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));

    private static final MethodHandle NOOP;

    static {
        try {
            NOOP = MethodHandles.lookup().findStatic(CtpLibrary.class, "noop", MethodType.methodType(void.class, MemorySegment.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final Map<CtpVersion, CtpLibrary> INSTANCES = new ConcurrentHashMap<>();

    private final CtpVersion version;
    private final MethodHandle createMdApi;
    private final MethodHandle mdApiVersion;
    private final MethodHandle createTraderApi;
    private final MethodHandle traderApiVersion;

    private CtpLibrary(CtpVersion version, Path dir) {
        this.version = version;
        Platform platform = Platform.current();
        SymbolLookup md = SymbolLookup.libraryLookup(dir.resolve(platform.mdLibrary), Arena.global());
        SymbolLookup trader = SymbolLookup.libraryLookup(dir.resolve(platform.traderLibrary), Arena.global());
        // static CThostFtdcMdApi *CreateFtdcMdApi(const char *pszFlowPath, const bool bIsUsingUdp, const bool bIsMulticast)
        createMdApi = LINKER.downcallHandle(find(md, platform.createMdApi),
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_BOOLEAN, JAVA_BOOLEAN));
        mdApiVersion = LINKER.downcallHandle(find(md, platform.mdApiVersion), FunctionDescriptor.of(ADDRESS));
        // static CThostFtdcTraderApi *CreateFtdcTraderApi(const char *pszFlowPath)
        createTraderApi = LINKER.downcallHandle(find(trader, platform.createTraderApi), FunctionDescriptor.of(ADDRESS, ADDRESS));
        traderApiVersion = LINKER.downcallHandle(find(trader, platform.traderApiVersion), FunctionDescriptor.of(ADDRESS));
    }

    /**
     * 从 classpath 解压随附的原生库并加载。
     */
    public static CtpLibrary load(CtpVersion version) {
        return load(version, null);
    }

    /**
     * 每个版本只加载一次，之后的调用直接返回已加载的实例（忽略 dir）。
     *
     * @param dir 原生库所在目录；为 null 时从 classpath 解压到临时目录
     * @throws IllegalStateException 原生库报告的版本与 version 不符（结构体布局不匹配，继续使用会破坏内存）
     */
    public static CtpLibrary load(CtpVersion version, Path dir) {
        CtpLibrary lib = INSTANCES.get(version);
        if (lib != null) {
            return lib;
        }
        synchronized (CtpLibrary.class) {
            return INSTANCES.computeIfAbsent(version, v -> {
                Path libraryDir = dir != null ? dir : extract(v);
                CtpLibrary loaded = new CtpLibrary(v, libraryDir);
                String md = loaded.mdApiVersion();
                String trader = loaded.traderApiVersion();
                if (!v.matches(md) || !v.matches(trader)) {
                    throw new IllegalStateException("CTP 原生库版本不符 期望 %s 行情 %s 交易 %s 目录 %s"
                        .formatted(v, md, trader, libraryDir));
                }
                log.info("CTP 原生库已加载 {} 行情 {} 交易 {}", libraryDir, md, trader);
                return loaded;
            });
        }
    }

    public CtpVersion version() {
        return version;
    }

    public String mdApiVersion() {
        try {
            return cString((MemorySegment) mdApiVersion.invokeExact());
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    public String traderApiVersion() {
        try {
            return cString((MemorySegment) traderApiVersion.invokeExact());
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    MemorySegment createMdApi(MemorySegment flowPath) {
        try {
            return notNull((MemorySegment) createMdApi.invokeExact(flowPath, false, false), "CreateFtdcMdApi");
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    MemorySegment createTraderApi(MemorySegment flowPath) {
        try {
            return notNull((MemorySegment) createTraderApi.invokeExact(flowPath), "CreateFtdcTraderApi");
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    /**
     * 取 C++ 对象 vtable 第 slot 个函数的地址。
     */
    static MemorySegment virtualFunction(MemorySegment self, int slot) {
        MemorySegment vtable = self.reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);
        return vtable.reinterpret((slot + 1) * ADDRESS.byteSize()).getAtIndex(ADDRESS, slot);
    }

    /**
     * 构造一个 C++ Spi 对象。未实现的槽位指向同一个空函数：x64 两种 ABI 均由调用方清理参数，
     * 忽略全部参数的 void 函数可以安全地充当任意签名的空回调。
     *
     * @param slots    Spi 类的虚函数个数
     * @param handlers 槽位 → 回调实现（已绑定接收者的方法句柄及其 C 签名）
     */
    static MemorySegment newSpi(Arena arena, int slots, Map<Integer, Upcall> handlers) {
        MemorySegment noop = LINKER.upcallStub(NOOP, FunctionDescriptor.ofVoid(ADDRESS), arena);
        MemorySegment vtable = arena.allocate(ADDRESS, slots);
        for (int i = 0; i < slots; i++) {
            Upcall upcall = handlers.get(i);
            vtable.setAtIndex(ADDRESS, i, upcall == null ? noop : LINKER.upcallStub(upcall.target(), upcall.descriptor(), arena));
        }
        MemorySegment spi = arena.allocate(ADDRESS);
        spi.set(ADDRESS, 0, vtable);
        return spi;
    }

    record Upcall(MethodHandle target, FunctionDescriptor descriptor) {
    }

    /**
     * 回调里拿到的结构体指针是零长度段，按结构体大小重新解释；空指针返回 null。
     */
    static MemorySegment struct(MemorySegment pointer, long size) {
        return pointer == null || MemorySegment.NULL.equals(pointer) ? null : pointer.reinterpret(size);
    }

    static String cString(MemorySegment pointer) {
        return CtpField.cString(pointer);
    }

    static RuntimeException rethrow(Throwable e) {
        if (e instanceof RuntimeException re) {
            return re;
        }
        if (e instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(e);
    }

    @SuppressWarnings("unused")
    private static void noop(MemorySegment self) {
    }

    private static MemorySegment notNull(MemorySegment p, String what) {
        if (MemorySegment.NULL.equals(p)) {
            throw new IllegalStateException(what + " 返回空指针，检查流文件目录是否存在且可写");
        }
        return p;
    }

    private static MemorySegment find(SymbolLookup lookup, String symbol) {
        return lookup.find(symbol).orElseThrow(() -> new UnsatisfiedLinkError("CTP 原生库缺少符号 " + symbol));
    }

    private static Path extract(CtpVersion version) {
        Platform platform = Platform.current();
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "ctp-" + version);
        try {
            Files.createDirectories(dir);
            for (String name : new String[]{platform.mdLibrary, platform.traderLibrary}) {
                try (InputStream in = CtpLibrary.class.getResourceAsStream("/" + version.resourceDir + "/" + name)) {
                    if (in == null) {
                        throw new IllegalStateException("classpath 中缺少 CTP 原生库 /" + version.resourceDir + "/" + name);
                    }
                    byte[] bytes = in.readAllBytes();
                    Path target = dir.resolve(name);
                    // 已被其他进程加载的 DLL 无法覆盖；内容相同就直接复用
                    if (Files.exists(target) && Files.size(target) == bytes.length) {
                        continue;
                    }
                    Path tmp = Files.createTempFile(dir, name, ".tmp");
                    Files.write(tmp, bytes);
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("解压 CTP 原生库失败: " + dir, e);
        }
        return dir;
    }

    private enum Platform {
        WINDOWS("thostmduserapi_se.dll", "thosttraderapi_se.dll",
            "?CreateFtdcMdApi@CThostFtdcMdApi@@SAPEAV1@PEBD_N1@Z",
            "?GetApiVersion@CThostFtdcMdApi@@SAPEBDXZ",
            "?CreateFtdcTraderApi@CThostFtdcTraderApi@@SAPEAV1@PEBD@Z",
            "?GetApiVersion@CThostFtdcTraderApi@@SAPEBDXZ"),
        LINUX("thostmduserapi_se.so", "thosttraderapi_se.so",
            "_ZN15CThostFtdcMdApi15CreateFtdcMdApiEPKcbb",
            "_ZN15CThostFtdcMdApi13GetApiVersionEv",
            "_ZN19CThostFtdcTraderApi19CreateFtdcTraderApiEPKc",
            "_ZN19CThostFtdcTraderApi13GetApiVersionEv"),
        ;

        final String mdLibrary;
        final String traderLibrary;
        final String createMdApi;
        final String mdApiVersion;
        final String createTraderApi;
        final String traderApiVersion;

        Platform(String mdLibrary, String traderLibrary, String createMdApi, String mdApiVersion,
                 String createTraderApi, String traderApiVersion) {
            this.mdLibrary = mdLibrary;
            this.traderLibrary = traderLibrary;
            this.createMdApi = createMdApi;
            this.mdApiVersion = mdApiVersion;
            this.createTraderApi = createTraderApi;
            this.traderApiVersion = traderApiVersion;
        }

        static Platform current() {
            String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
            if (!arch.equals("amd64") && !arch.equals("x86_64")) {
                throw new UnsupportedOperationException("CTP 原生库仅提供 x64 版本，当前架构 " + arch);
            }
            String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                return WINDOWS;
            }
            if (os.contains("linux")) {
                return LINUX;
            }
            throw new UnsupportedOperationException("CTP 原生库不支持当前操作系统 " + os);
        }
    }
}
