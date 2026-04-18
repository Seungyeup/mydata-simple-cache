package com.mydata.cache; // 패키지 경로를 선언한다.

import java.util.Collections; // 불변 Map 래핑을 위해 가져온다.
import java.util.Map; // 메타 정보 저장 구조로 Map을 사용한다.

public final class ApiEndpointRoutingSnapshot { // ApiCommonMeta의 구성 요소(클래스2)이며, 3개의 Map을 가진 불변 스냅샷이다.

    private static final ApiEndpointRoutingSnapshot EMPTY = new ApiEndpointRoutingSnapshot( // 빈 클래스2 스냅샷이다.
            Collections.<String, String>emptyMap(), // API 코드 Map
            Collections.<String, String>emptyMap(), // URL Map
            Collections.<String, String>emptyMap() // HTTP 메서드 Map
    );

    private final Map<String, String> apiCodeByApiCode; // API 코드(키) -> API 코드(값) Map이다.
    private final Map<String, String> apiUrlByApiCode; // API 코드(키) -> URL(값) Map이다.
    private final Map<String, String> httpMethodByApiCode; // API 코드(키) -> HTTP 메서드(값) Map이다.

    public ApiEndpointRoutingSnapshot(Map<String, String> apiCodeByApiCode, Map<String, String> apiUrlByApiCode, Map<String, String> httpMethodByApiCode) { // 3개의 Map으로 클래스2를 만든다.
        this.apiCodeByApiCode = FreezeUtils.freezeStringMap(apiCodeByApiCode); // 외부 변경을 막기 위해 방어적으로 복사/불변화한다.
        this.apiUrlByApiCode = FreezeUtils.freezeStringMap(apiUrlByApiCode); // 외부 변경을 막기 위해 방어적으로 복사/불변화한다.
        this.httpMethodByApiCode = FreezeUtils.freezeStringMap(httpMethodByApiCode); // 외부 변경을 막기 위해 방어적으로 복사/불변화한다.
    }

    public static ApiEndpointRoutingSnapshot empty() { // 빈 클래스2 스냅샷을 반환한다.
        return EMPTY; // 싱글톤을 재사용한다.
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

    public int size() { // 클래스2의 엔트리 수를 반환한다.
        return apiCodeByApiCode.size(); // 키 집합은 apiCodeMap을 기준으로 한다.
    }

    // Map freeze 로직은 FreezeUtils로 공통화했다.
}
