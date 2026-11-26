/*
 * Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 */
package ai.philterd.batch.run;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;

/** JSON for the coordination formats and output records: snake_case keys, ISO-8601 times, nulls left out. */
public final class Json {

    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    private Json() {
    }

    public static byte[] write(final Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (final JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static <T> T read(final byte[] data, final Class<T> type) {
        try {
            return MAPPER.readValue(data, type);
        } catch (final IOException e) {
            // The class only: a parser message can quote content, and these objects hold input keys.
            throw new FormatException("Could not read a " + type.getSimpleName() + " (" + e.getClass().getSimpleName() + ").", e);
        }
    }

}
