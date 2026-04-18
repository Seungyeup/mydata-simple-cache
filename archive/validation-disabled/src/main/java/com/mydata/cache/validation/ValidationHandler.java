package com.mydata.cache.validation;

import com.mydata.cache.ApiId;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class ValidationHandler {

    private final ApiValidationMetaCache cache;

    public ValidationHandler(ApiValidationMetaCache cache) {
        if (cache == null) {
            throw new IllegalArgumentException("cache must not be null");
        }
        this.cache = cache;
    }

    public void validateInputOrThrow(String apiCode, JsonNode input) {
        validateInputOrThrow(ApiId.DEFAULT_VERSION, apiCode, input);
    }

    public void validateInputOrThrow(String version, String apiCode, JsonNode input) {
        ApiValidationMeta meta = cache.snapshot();
        Set<String> requiredPointers = meta.getRequiredInputJsonPointersOrEmpty(version, apiCode);
        validateRequiredPointersOrThrow(version + "/" + apiCode, "input", requiredPointers, input);
    }

    public void validateOutputOrThrow(String apiCode, JsonNode output) {
        validateOutputOrThrow(ApiId.DEFAULT_VERSION, apiCode, output);
    }

    public void validateOutputOrThrow(String version, String apiCode, JsonNode output) {
        ApiValidationMeta meta = cache.snapshot();
        Set<String> requiredPointers = meta.getRequiredOutputJsonPointersOrEmpty(version, apiCode);
        validateRequiredPointersOrThrow(version + "/" + apiCode, "output", requiredPointers, output);
    }

    private static void validateRequiredPointersOrThrow(String apiCode, String ioLabel, Set<String> requiredPointers, JsonNode root) {
        if (requiredPointers == null || requiredPointers.isEmpty()) {
            return;
        }

        List<String> missing = null;
        for (String pointer : requiredPointers) {
            if (pointer == null) {
                continue;
            }
            if (!JsonNodePointerSupport.isPresentNonNullAtJsonPointer(root, pointer)) {
                if (missing == null) {
                    missing = new ArrayList<String>();
                }
                missing.add(pointer);
            }
        }

        if (missing == null || missing.isEmpty()) {
            return;
        }

        Collections.sort(missing);
        throw new ValidationException("required field missing: apiCode=" + apiCode + ", io=" + ioLabel + ", missing=" + missing);
    }

    public static final class ValidationException extends RuntimeException {
        public ValidationException(String message) {
            super(message);
        }
    }
}
