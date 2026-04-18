package com.mydata.cache;

import java.nio.charset.StandardCharsets;

public final class CacheReloadLogSupport {

    static final String TOPIC = "MDC_CACHE_LOG";
    private static final int MAX_MESSAGE_BYTES = 900 * 1024;

    private CacheReloadLogSupport() {
    }

    public static void publishSafely(CacheReloadLogPublisher publisher, String message) {
        if (publisher == null || message == null) {
            return;
        }
        try {
            publisher.publish(TOPIC, ensureMaxBytes(message));
        } catch (Exception e) {
            return;
        }
    }

    public static String toJson(String cacheName,
                         String reloadType,
                         boolean success,
                         long startedAtEpochMillis,
                         long durationMillis,
                         int size,
                         int diffAdded,
                         int diffRemoved,
                         int diffChanged,
                         Exception errorOrNull) {

        String errorClass = null;
        String errorMessage = null;
        if (errorOrNull != null) {
            errorClass = errorOrNull.getClass().getName();
            errorMessage = errorOrNull.getMessage();
        }

        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        appendJsonStringField(sb, "cache", cacheName);
        sb.append(',');
        appendJsonStringField(sb, "reloadType", reloadType);
        sb.append(',');
        sb.append('"').append("success").append('"').append(':').append(success);
        sb.append(',');
        sb.append('"').append("startedAtEpochMillis").append('"').append(':').append(startedAtEpochMillis);
        sb.append(',');
        sb.append('"').append("durationMillis").append('"').append(':').append(durationMillis);
        sb.append(',');
        sb.append('"').append("size").append('"').append(':').append(size);

        if (diffAdded >= 0 || diffRemoved >= 0 || diffChanged >= 0) {
            sb.append(',');
            sb.append('"').append("diff").append('"').append(':');
            sb.append('{');
            sb.append('"').append("added").append('"').append(':').append(Math.max(0, diffAdded));
            sb.append(',');
            sb.append('"').append("removed").append('"').append(':').append(Math.max(0, diffRemoved));
            sb.append(',');
            sb.append('"').append("changed").append('"').append(':').append(Math.max(0, diffChanged));
            sb.append('}');
        }

        if (errorClass != null) {
            sb.append(',');
            appendJsonStringField(sb, "errorClass", errorClass);
        }
        if (errorMessage != null) {
            sb.append(',');
            appendJsonStringField(sb, "errorMessage", truncate(errorMessage, 300));
        }
        sb.append('}');
        return ensureMaxBytes(sb.toString());
    }

    private static String ensureMaxBytes(String message) {
        if (message == null) {
            return null;
        }
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_MESSAGE_BYTES) {
            return message;
        }

        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        appendJsonStringField(sb, "truncated", "true");
        sb.append(',');
        sb.append('"').append("originalBytes").append('"').append(':').append(bytes.length);
        sb.append(',');
        appendJsonStringField(sb, "preview", truncate(message, 300));
        sb.append('}');
        return sb.toString();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return null;
        }
        if (maxLen <= 0) {
            return "";
        }
        if (s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen);
    }

    private static void appendJsonStringField(StringBuilder sb, String name, String value) {
        sb.append('"');
        sb.append(escapeJson(name));
        sb.append('"');
        sb.append(':');
        if (value == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        sb.append(escapeJson(value));
        sb.append('"');
    }

    private static String escapeJson(String s) {
        if (s == null || s.isEmpty()) {
            return (s == null ? null : "");
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                sb.append("\\\"");
            } else if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
