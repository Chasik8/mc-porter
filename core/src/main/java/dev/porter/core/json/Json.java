package dev.porter.core.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Shared, preconfigured Jackson 3 mappers. Use these everywhere so output is identical across modules
 * (pretty-printed, nulls omitted, insertion order kept).
 */
public final class Json {
    private static final JsonInclude.Value NON_NULL =
            JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL);

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .changeDefaultPropertyInclusion(v -> NON_NULL)
            .build();

    private static final YAMLMapper YAML = YAMLMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .changeDefaultPropertyInclusion(v -> NON_NULL)
            .build();

    private Json() {
    }

    public static JsonMapper json() {
        return JSON;
    }

    public static YAMLMapper yaml() {
        return YAML;
    }
}
