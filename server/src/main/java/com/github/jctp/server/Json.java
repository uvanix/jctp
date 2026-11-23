package com.github.jctp.server;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * JSON 约定：日期 "2026-11-13"、时间 "21:00:01"、Duration "PT30S"；
 * CTP 的无效价格在 core 中已转为 NaN，序列化为 null（JSON 没有 NaN）。
 */
public final class Json {

    public static final ObjectMapper MAPPER = create();

    private Json() {
    }

    static ObjectMapper create() {
        SimpleModule nan = new SimpleModule("nan-as-null");
        JsonSerializer<Double> serializer = new JsonSerializer<>() {
            @Override
            public void serialize(Double value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                if (value == null || value.isNaN() || value.isInfinite()) {
                    gen.writeNull();
                } else {
                    gen.writeNumber(value);
                }
            }
        };
        nan.addSerializer(Double.class, serializer);
        nan.addSerializer(double.class, serializer);
        return JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .addModule(nan)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
