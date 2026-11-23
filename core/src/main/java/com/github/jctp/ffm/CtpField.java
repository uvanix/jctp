package com.github.jctp.ffm;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.nio.charset.Charset;

import static java.lang.foreign.ValueLayout.*;

/**
 * CTP 结构体中的一个字段。CTP 字段只有 char[N]、char、short、int、double 五种，字符串为 GBK 编码、NUL 结尾。
 *
 * @param offset 字段在结构体内的字节偏移，-1 表示当前 CTP 版本没有该字段
 * @param length 字节长度（字符串含结尾 NUL）
 */
public record CtpField(long offset, int length, Kind kind) {

    public static final Charset GBK = Charset.forName("GBK");

    public enum Kind {STRING, CHAR, SHORT, INT, DOUBLE}

    /**
     * 分配 GBK 编码、NUL 结尾的 C 字符串（FFM 自带的 allocateFrom 只支持标准字符集）。
     */
    public static MemorySegment cString(SegmentAllocator allocator, String value) {
        byte[] bytes = value.getBytes(GBK);
        MemorySegment segment = allocator.allocate(bytes.length + 1L);
        MemorySegment.copy(bytes, 0, segment, JAVA_BYTE, 0, bytes.length);
        segment.set(JAVA_BYTE, bytes.length, (byte) 0);
        return segment;
    }

    /**
     * 读取 GBK 编码、NUL 结尾的 C 字符串，空指针返回空串。
     */
    public static String cString(MemorySegment pointer) {
        if (pointer == null || MemorySegment.NULL.equals(pointer)) {
            return "";
        }
        MemorySegment s = pointer.reinterpret(Long.MAX_VALUE);
        long n = 0;
        while (s.get(JAVA_BYTE, n) != 0) {
            n++;
        }
        return new String(s.asSlice(0, n).toArray(JAVA_BYTE), GBK);
    }

    /**
     * 当前版本不存在的字段：读取返回缺省值（""、0、'\0'），写入抛异常。
     */
    static CtpField absent(Kind kind) {
        return new CtpField(-1, 0, kind);
    }

    public boolean present() {
        return offset >= 0;
    }

    static CtpField str(long offset, int length) {
        return new CtpField(offset, length, Kind.STRING);
    }

    static CtpField chr(long offset) {
        return new CtpField(offset, 1, Kind.CHAR);
    }

    static CtpField i16(long offset) {
        return new CtpField(offset, 2, Kind.SHORT);
    }

    static CtpField i32(long offset) {
        return new CtpField(offset, 4, Kind.INT);
    }

    static CtpField f64(long offset) {
        return new CtpField(offset, 8, Kind.DOUBLE);
    }

    public String getString(MemorySegment struct) {
        return getRawString(struct).trim();
    }

    /**
     * 到首个 NUL 为止的原始字节，不解码。分段返回的长文本（如结算单）须拼接字节后再解码，
     * 否则在段边界被截断的 GBK 汉字会乱码。
     */
    public byte[] getBytes(MemorySegment struct) {
        check(Kind.STRING);
        if (!present()) {
            return new byte[0];
        }
        int n = 0;
        while (n < length && struct.get(JAVA_BYTE, offset + n) != 0) {
            n++;
        }
        byte[] bytes = new byte[n];
        MemorySegment.copy(struct, JAVA_BYTE, offset, bytes, 0, n);
        return bytes;
    }

    /**
     * 不去首尾空格的原值。上期所等交易所的报单编号右对齐、前补空格，按报单编号撤单须原样回填。
     */
    public String getRawString(MemorySegment struct) {
        return new String(getBytes(struct), GBK);
    }

    public char getChar(MemorySegment struct) {
        check(Kind.CHAR);
        if (!present()) {
            return '\0';
        }
        return (char) (struct.get(JAVA_BYTE, offset) & 0xFF);
    }

    public int getInt(MemorySegment struct) {
        check(Kind.INT);
        if (!present()) {
            return 0;
        }
        return struct.get(JAVA_INT_UNALIGNED, offset);
    }

    public double getDouble(MemorySegment struct) {
        check(Kind.DOUBLE);
        if (!present()) {
            return 0;
        }
        return struct.get(JAVA_DOUBLE_UNALIGNED, offset);
    }

    /**
     * 写入字符串，超长直接拒绝（截断合约代码、报单引用之类的字段会造成静默错单）。
     */
    public void set(MemorySegment struct, String value) {
        checkWritable(Kind.STRING);
        byte[] bytes = value == null ? new byte[0] : value.getBytes(GBK);
        if (bytes.length >= length) {
            throw new IllegalArgumentException("字段超长(最大 %d 字节): %s".formatted(length - 1, value));
        }
        struct.asSlice(offset, length).fill((byte) 0);
        MemorySegment.copy(bytes, 0, struct, JAVA_BYTE, offset, bytes.length);
    }

    public void set(MemorySegment struct, char value) {
        checkWritable(Kind.CHAR);
        struct.set(JAVA_BYTE, offset, (byte) value);
    }

    public void set(MemorySegment struct, int value) {
        checkWritable(Kind.INT);
        struct.set(JAVA_INT_UNALIGNED, offset, value);
    }

    public void set(MemorySegment struct, double value) {
        checkWritable(Kind.DOUBLE);
        struct.set(JAVA_DOUBLE_UNALIGNED, offset, value);
    }

    private void check(Kind expected) {
        if (kind != expected) {
            throw new IllegalStateException("字段类型为 %s，不能按 %s 访问".formatted(kind, expected));
        }
    }

    private void checkWritable(Kind expected) {
        check(expected);
        if (!present()) {
            throw new UnsupportedOperationException("当前 CTP 版本没有该字段");
        }
    }
}
