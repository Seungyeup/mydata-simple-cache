package com.mydata.cache;

import java.util.Locale;
import java.util.Objects;
public final class ApiId {

    public static final String DEFAULT_VERSION = "v1";

    private final String version;
    private final String apiCode;

    private ApiId(String version, String apiCode) {
        this.version = version;
        this.apiCode = apiCode;
    }

    public static ApiId of(String version, String apiCode) {
        String v = normalizeVersionOrNull(version);
        if (v == null) {
            throw new IllegalArgumentException("version must not be null/blank");
        }
        String c = normalizeApiCodeOrNull(apiCode);
        if (c == null) {
            throw new IllegalArgumentException("apiCode must not be null/blank");
        }
        return new ApiId(v, c);
    }

    public static ApiId ofOrDefault(String versionOrNull, String apiCode) {
        String v = normalizeVersionOrNull(versionOrNull);
        if (v == null) {
            v = DEFAULT_VERSION;
        }
        String c = normalizeApiCodeOrNull(apiCode);
        if (c == null) {
            throw new IllegalArgumentException("apiCode must not be null/blank");
        }
        return new ApiId(v, c);
    }

    public String getVersion() {
        return version;
    }

    public String getApiCode() {
        return apiCode;
    }

    private static String normalizeVersionOrNull(String versionOrNull) {
        if (versionOrNull == null) {
            return null;
        }
        String v = versionOrNull.trim();
        if (v.isEmpty()) {
            return null;
        }
        return v.toLowerCase(Locale.ROOT);
    }

    private static String normalizeApiCodeOrNull(String apiCodeOrNull) {
        if (apiCodeOrNull == null) {
            return null;
        }
        String c = apiCodeOrNull.trim();
        if (c.isEmpty()) {
            return null;
        }
        return c.toUpperCase(Locale.ROOT);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ApiId)) {
            return false;
        }
        ApiId that = (ApiId) o;
        return version.equals(that.version) && apiCode.equals(that.apiCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, apiCode);
    }

    @Override
    public String toString() {
        return version + "/" + apiCode;
    }
}
