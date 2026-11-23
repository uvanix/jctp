package com.github.jctp.ffm;

import java.util.Locale;

/**
 * 随附的 CTP API 版本。各版本的原生库、结构体布局、Spi 槽位不同，同一进程内可以同时加载多个版本。
 * <p>
 * 声明顺序即 {@link CtpStructs}、{@link CtpVtables} 中按版本取值的顺序，新增版本须同步重新生成。
 */
public enum CtpVersion {

    V6_3_15("6.3.15", "jctpv6v3v15x64api"),
    /**
     * 合约代码类字段由 31 字节扩至 81 字节并移到结构体末尾；MdSpi 新增 OnRspQryMulticastInstrument
     */
    V6_5_1("6.5.1", "jctpv6v5v1x64api"),
    /**
     * TraderApi/Spi 新增方法，ReqQryInvestorPositionDetail 及其后的回调槽位顺延；登录应答、深度行情末尾新增字段
     */
    V6_6_8("6.6.8", "jctpv6v6v8x64api"),
    V6_7_2("6.7.2", "jctpv6v7v2x64api"),
    /**
     * 测评版（穿透式监管认证测试用）：头文件与同号生产版相同，原生库不同
     */
    V6_7_2_CP("6.7.2_CP", "jctpv6v7v2CPx64api"),
    V6_7_8("6.7.8", "jctpv6v7v8x64api"),
    V6_7_8_CP("6.7.8_CP", "jctpv6v7v8CPx64api"),
    ;

    private static final String EVALUATION_SUFFIX = "_CP";

    /**
     * 版本号，如 6.3.15；测评版带 _CP 后缀，如 6.7.8_CP
     */
    public final String number;
    /**
     * classpath 中原生库所在目录
     */
    final String resourceDir;

    CtpVersion(String number, String resourceDir) {
        this.number = number;
        this.resourceDir = resourceDir;
    }

    public CtpStructs structs() {
        return CtpStructs.of(this);
    }

    /**
     * 原生库报告的版本串（如 "v6.7.8_CP_20240802 13:08:06"）是否属于本版本；生产版与测评版互不匹配。
     */
    boolean matches(String apiVersion) {
        boolean evaluation = number.endsWith(EVALUATION_SUFFIX);
        return apiVersion.contains(number) && (evaluation || !apiVersion.contains(EVALUATION_SUFFIX));
    }

    /**
     * 解析 "6.7.8"、"v6.7.8"、"V6_7_8"、"6.7.8_CP"、"V6_7_8_CP"，空值返回 {@link #V6_3_15}。
     */
    public static CtpVersion parse(String value) {
        if (value == null || value.isBlank()) {
            return V6_3_15;
        }
        String key = normalize(value);
        for (CtpVersion v : values()) {
            if (normalize(v.number).equals(key)) {
                return v;
            }
        }
        throw new IllegalArgumentException("不支持的 CTP 版本 " + value);
    }

    private static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT).replaceFirst("^v", "").replace('_', '.').replace('-', '.');
    }

    @Override
    public String toString() {
        return "v" + number;
    }
}
