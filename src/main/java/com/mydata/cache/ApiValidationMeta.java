package com.mydata.cache;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * API 코드(예: BA01)별로 JSON input/output의 필수 필드(경로) 정보를 담는 불변 스냅샷 모델이다.
 *
 * 경로 표현은 JSON Pointer(RFC 6901) 형태를 사용한다. 예: "/payer/id", "/amount"
 */
public final class ApiValidationMeta {

    private static final ApiValidationMeta EMPTY = new ApiValidationMeta(
            Collections.<ApiId, Set<String>>emptyMap(),
            Collections.<ApiId, Set<String>>emptyMap()
    );

    private final Map<ApiId, Set<String>> requiredInputJsonPointersByApiId;
    private final Map<ApiId, Set<String>> requiredOutputJsonPointersByApiId;
    private final Set<ApiId> apiIds;

    public ApiValidationMeta(Map<ApiId, Set<String>> requiredInputJsonPointersByApiId,
                             Map<ApiId, Set<String>> requiredOutputJsonPointersByApiId) {
        this.requiredInputJsonPointersByApiId = freezeApiIdSetMap(requiredInputJsonPointersByApiId);
        this.requiredOutputJsonPointersByApiId = freezeApiIdSetMap(requiredOutputJsonPointersByApiId);
        this.apiIds = freezeApiIds(this.requiredInputJsonPointersByApiId, this.requiredOutputJsonPointersByApiId);
    }

    public static ApiValidationMeta empty() {
        return EMPTY;
    }

    public Map<ApiId, Set<String>> requiredInputJsonPointersByApiIdView() {
        return requiredInputJsonPointersByApiId;
    }

    public Map<ApiId, Set<String>> requiredOutputJsonPointersByApiIdView() {
        return requiredOutputJsonPointersByApiId;
    }

    public Set<ApiId> apiIdsView() {
        return apiIds;
    }

    public int size() {
        return apiIds.size();
    }

    public Set<String> getRequiredInputJsonPointersOrEmpty(String apiCode) {
        if (apiCode == null || apiCode.isEmpty()) {
            return Collections.<String>emptySet();
        }
        return getRequiredInputJsonPointersOrEmpty(ApiId.ofOrDefault(null, apiCode));
    }

    public Set<String> getRequiredOutputJsonPointersOrEmpty(String apiCode) {
        if (apiCode == null || apiCode.isEmpty()) {
            return Collections.<String>emptySet();
        }
        return getRequiredOutputJsonPointersOrEmpty(ApiId.ofOrDefault(null, apiCode));
    }

    public Set<String> getRequiredInputJsonPointersOrEmpty(String version, String apiCode) {
        return getRequiredInputJsonPointersOrEmpty(ApiId.of(version, apiCode));
    }

    public Set<String> getRequiredOutputJsonPointersOrEmpty(String version, String apiCode) {
        return getRequiredOutputJsonPointersOrEmpty(ApiId.of(version, apiCode));
    }

    public Set<String> getRequiredInputJsonPointersOrEmpty(ApiId apiId) {
        if (apiId == null) {
            return Collections.<String>emptySet();
        }
        Set<String> set = requiredInputJsonPointersByApiId.get(apiId);
        return (set == null ? Collections.<String>emptySet() : set);
    }

    public Set<String> getRequiredOutputJsonPointersOrEmpty(ApiId apiId) {
        if (apiId == null) {
            return Collections.<String>emptySet();
        }
        Set<String> set = requiredOutputJsonPointersByApiId.get(apiId);
        return (set == null ? Collections.<String>emptySet() : set);
    }

    private static Set<ApiId> freezeApiIds(Map<ApiId, Set<String>> input, Map<ApiId, Set<String>> output) {
        if ((input == null || input.isEmpty()) && (output == null || output.isEmpty())) {
            return Collections.<ApiId>emptySet();
        }
        HashSet<ApiId> set = new HashSet<ApiId>();
        if (input != null) {
            set.addAll(input.keySet());
        }
        if (output != null) {
            set.addAll(output.keySet());
        }
        if (set.isEmpty()) {
            return Collections.<ApiId>emptySet();
        }
        return Collections.unmodifiableSet(set);
    }

    private static Map<ApiId, Set<String>> freezeApiIdSetMap(Map<ApiId, Set<String>> source) {
        if (source == null || source.isEmpty()) {
            return Collections.<ApiId, Set<String>>emptyMap();
        }

        Map<ApiId, Set<String>> copy = new HashMap<ApiId, Set<String>>(source.size() * 2);
        for (Map.Entry<ApiId, Set<String>> entry : source.entrySet()) {
            if (entry == null) {
                continue;
            }
            ApiId key = entry.getKey();
            if (key == null) {
                continue;
            }

            Set<String> raw = entry.getValue();
            if (raw == null || raw.isEmpty()) {
                continue;
            }

            Set<String> setCopy = new HashSet<String>(raw.size() * 2);
            for (String v : raw) {
                if (v == null || v.isEmpty() || "/".equals(v)) {
                    continue;
                }
                setCopy.add(v);
            }

            if (setCopy.isEmpty()) {
                continue;
            }

            copy.put(key, Collections.unmodifiableSet(setCopy));
        }

        if (copy.isEmpty()) {
            return Collections.<ApiId, Set<String>>emptyMap();
        }

        return Collections.unmodifiableMap(copy);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ApiValidationMeta)) {
            return false;
        }
        ApiValidationMeta that = (ApiValidationMeta) o;
        return requiredInputJsonPointersByApiId.equals(that.requiredInputJsonPointersByApiId)
                && requiredOutputJsonPointersByApiId.equals(that.requiredOutputJsonPointersByApiId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(requiredInputJsonPointersByApiId, requiredOutputJsonPointersByApiId);
    }

    @Override
    public String toString() {
        return "ApiValidationMeta{" + "apiCount=" + apiIds.size() + '}';
    }
}
