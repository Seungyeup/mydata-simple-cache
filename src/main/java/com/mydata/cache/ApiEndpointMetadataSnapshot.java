package com.mydata.cache; // 패키지 경로를 선언한다.

import java.util.Collections; // 불변 Map 래핑을 위해 가져온다.
import java.util.HashMap; // 방어적 복사를 위해 가져온다.
import java.util.HashSet; // Set 방어적 복사를 위해 가져온다.
import java.util.Map; // 메타 정보 저장 구조로 Map을 사용한다.
import java.util.Set; // Map<String, Set<String>> 타입을 사용한다.

public final class ApiEndpointMetadataSnapshot { // ApiCommonMeta의 구성 요소(클래스1)이며, 3개의 Map + String을 가진 불변 스냅샷이다.

    private static final ApiEndpointMetadataSnapshot EMPTY = new ApiEndpointMetadataSnapshot( // 빈 클래스1 스냅샷이다.
            null, // 예시 문자열 필드는 빈 스냅샷에서는 null로 둔다.
            Collections.<String, String>emptyMap(), // API 코드 Map
            Collections.<String, String>emptyMap(), // URL Map
            Collections.<String, String>emptyMap(), // HTTP 메서드 Map
            Collections.<String, Set<String>>emptyMap() // Map<String, Set<String>> 형태의 추가 메타 Map
    );

    private final String version; // 예시: 스냅샷 버전/출처 같은 문자열 메타(불변 String).
    private final Map<String, String> apiCodeByApiCode; // API 코드(키) -> API 코드(값) Map이다.
    private final Map<String, String> apiUrlByApiCode; // API 코드(키) -> URL(값) Map이다.
    private final Map<String, String> httpMethodByApiCode; // API 코드(키) -> HTTP 메서드(값) Map이다.
    private final Map<String, Set<String>> stringSetByApiCode; // 예시: API 코드(키) -> 문자열 Set(값) 형태의 추가 메타 Map이다.

    public ApiEndpointMetadataSnapshot(Map<String, String> apiCodeByApiCode, Map<String, String> apiUrlByApiCode, Map<String, String> httpMethodByApiCode) { // 기존 생성자(호환)다.
        this(null, apiCodeByApiCode, apiUrlByApiCode, httpMethodByApiCode, null); // 추가 Map은 null로 두고 위임한다.
    }

    public ApiEndpointMetadataSnapshot(String version, Map<String, String> apiCodeByApiCode, Map<String, String> apiUrlByApiCode, Map<String, String> httpMethodByApiCode) { // 문자열 + 3개의 Map으로 클래스1을 만든다.
        this(version, apiCodeByApiCode, apiUrlByApiCode, httpMethodByApiCode, null); // 추가 Map은 null로 두고 위임한다.
    }

    public ApiEndpointMetadataSnapshot(String version, Map<String, String> apiCodeByApiCode, Map<String, String> apiUrlByApiCode, Map<String, String> httpMethodByApiCode, Map<String, Set<String>> stringSetByApiCode) { // 문자열 + 3개의 Map + (Map<String,Set<String>>)으로 클래스1을 만든다.
        this.version = version; // String은 불변이므로 참조만 final로 고정하면 된다(값 자체는 변하지 않는다).
        this.apiCodeByApiCode = FreezeUtils.freezeStringMap(apiCodeByApiCode); // 외부 변경을 막기 위해 방어적으로 복사/불변화한다.
        this.apiUrlByApiCode = FreezeUtils.freezeStringMap(apiUrlByApiCode); // 외부 변경을 막기 위해 방어적으로 복사/불변화한다.
        this.httpMethodByApiCode = FreezeUtils.freezeStringMap(httpMethodByApiCode);
        this.stringSetByApiCode = freezeStringSetMap(stringSetByApiCode); // Map과 Set 모두 방어적 복사 + 불변화한다(딥 프리즈).
    }

    public static ApiEndpointMetadataSnapshot empty() { // 빈 클래스1 스냅샷을 반환한다.
        return EMPTY; // 싱글톤을 재사용한다.
    }

    public String getVersionOrNull() { // 예시 문자열 필드를 반환한다.
        return version; // String은 불변이라 그대로 반환해도 안전하다.
    }

    public Map<String, String> apiCodeByApiCodeView() { // API 코드 Map 뷰를 반환한다.
        return apiCodeByApiCode; // 불변 Map이므로 그대로 반환해도 안전하다.
    }

    public Map<String, String> apiUrlByApiCodeView() { // URL Map 뷰를 반환한다.
        return apiUrlByApiCode; // 불변 Map이므로 그대로 반환해도 안전하다.
    }

    public Map<String, String> httpMethodByApiCodeView() { // HTTP 메서드 Map 뷰를 반환한다.
        return httpMethodByApiCode; // 불변 Map이므로 그대로 반환해도 안전하다.
    }

    public Map<String, Set<String>> stringSetByApiCodeView() { // Map<String, Set<String>> 형태의 추가 메타 Map 뷰를 반환한다.
        return stringSetByApiCode; // Map/Set 모두 불변으로 보관하므로 그대로 반환해도 안전하다.
    }

    public int size() { // 클래스1의 엔트리 수를 반환한다.
        return apiCodeByApiCode.size(); // 키 집합은 apiCodeMap을 기준으로 한다.
    }

    public String getApiCodeOrNull(String apiCode) { // API 코드 존재/정규화 조회다.
        if (apiCode == null || apiCode.isEmpty()) {
            return null; // 결과도 없다.
        }
        return apiCodeByApiCode.get(apiCode);
    }

    public String getApiUrlOrNull(String apiCode) { // API 코드로 URL을 조회한다.
        if (apiCode == null || apiCode.isEmpty()) {
            return null; // 결과도 없다.
        }
        return apiUrlByApiCode.get(apiCode);
    }

    public String getHttpMethodOrNull(String apiCode) { // API 코드로 HTTP 메서드를 조회한다.
        if (apiCode == null || apiCode.isEmpty()) {
            return null; // 결과도 없다.
        }
        return httpMethodByApiCode.get(apiCode);
    }

    private static Map<String, Set<String>> freezeStringSetMap(Map<String, Set<String>> source) { // Map<String, Set<String>>을 딥 프리즈(내용까지 불변)한다.
        if (source == null || source.isEmpty()) { // null/빈이면
            return Collections.<String, Set<String>>emptyMap(); // 빈 불변 Map을 반환한다.
        }

        Map<String, Set<String>> copy = new HashMap<String, Set<String>>(source.size() * 2); // 방어적 복사용 Map을 만든다.
        for (Map.Entry<String, Set<String>> entry : source.entrySet()) { // 모든 엔트리를 순회한다.
            if (entry == null) { // 엔트리 자체가 null이면
                continue; // 무시한다.
            }
            String key = entry.getKey();
            if (key == null || key.isEmpty()) {
                continue; // 제외한다.
            }

            Set<String> rawSet = entry.getValue(); // 원본 Set을 읽는다.
            if (rawSet == null || rawSet.isEmpty()) { // null/빈이면
                continue; // 의미 없으므로 제외한다.
            }

            Set<String> setCopy = new HashSet<String>(rawSet.size() * 2); // Set도 방어적 복사를 한다.
            for (String v : rawSet) { // Set의 값을 순회한다.
                if (v == null || v.isEmpty()) {
                    continue;
                }
                setCopy.add(v);
            }

            if (setCopy.isEmpty()) { // 정규화 후 비면
                continue; // 제외한다.
            }

            copy.put(key, Collections.unmodifiableSet(setCopy)); // Set을 불변으로 감싸고 Map에 넣는다.
        }

        if (copy.isEmpty()) { // 정리 후 비면
            return Collections.<String, Set<String>>emptyMap(); // 빈 불변 Map을 반환한다.
        }

        return Collections.unmodifiableMap(copy); // Map도 불변으로 감싸서 반환한다.
    }
}
