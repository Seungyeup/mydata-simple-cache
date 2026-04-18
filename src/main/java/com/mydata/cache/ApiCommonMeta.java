package com.mydata.cache; // 패키지 경로를 선언한다(프로젝트 내 네임스페이스 역할).

import java.util.Objects; // null 체크/동등성 비교를 표준 유틸로 처리하기 위해 가져온다.
import java.util.Map; // Map 뷰 메서드의 반환 타입으로 사용한다.

/**
 * API 코드(예: BA01)로부터 찾을 수 있는 공통 메타데이터의 "스냅샷" 모델이다.
 *
 * 요구사항:
 * - 캐시는 Map<String, ApiCommonMeta>가 아니라 ApiCommonMeta 단일 객체(스냅샷)로 들고
 * - ApiCommonMeta 내부에 3개의 Map<String, String>으로 메타정보를 보관한다.
 */
public final class ApiCommonMeta { // 불변(immutable) 객체로 만들어 스냅샷 캐시에 안전하게 담는다.

    private static final ApiCommonMeta EMPTY = new ApiCommonMeta( // 빈 스냅샷 싱글톤을 만든다.
            ApiEndpointMetadataSnapshot.empty(), // 클래스1(빈 스냅샷)
            ApiEndpointRoutingSnapshot.empty() // 클래스2(빈 스냅샷)
    );

    private final ApiEndpointMetadataSnapshot endpointMetadata; // ApiCommonMeta 내부 필드 클래스1이다(각각 3개 Map을 가진다).
    private final ApiEndpointRoutingSnapshot endpointRouting; // ApiCommonMeta 내부 필드 클래스2이다(각각 3개 Map을 가진다).

    public ApiCommonMeta(ApiEndpointMetadataSnapshot endpointMetadata, ApiEndpointRoutingSnapshot endpointRouting) { // 클래스1/클래스2로 스냅샷을 만든다.
        this.endpointMetadata = (endpointMetadata == null ? ApiEndpointMetadataSnapshot.empty() : endpointMetadata); // null이면 빈 스냅샷으로 대체한다.
        this.endpointRouting = (endpointRouting == null ? ApiEndpointRoutingSnapshot.empty() : endpointRouting); // null이면 빈 스냅샷으로 대체한다.
    }

    public static ApiCommonMeta empty() { // 빈 스냅샷을 반환한다.
        return EMPTY; // 불변 싱글톤을 재사용한다.
    }

    public ApiEndpointMetadataSnapshot endpointMetadata() { // 클래스1 스냅샷을 반환한다.
        return endpointMetadata; // 불변 객체이므로 그대로 반환해도 안전하다.
    }

    public ApiEndpointRoutingSnapshot endpointRouting() { // 클래스2 스냅샷을 반환한다.
        return endpointRouting; // 불변 객체이므로 그대로 반환해도 안전하다.
    }

    public Map<String, String> apiCodeByApiCodeView() { // API 코드 Map의 읽기 전용 뷰를 반환한다.
        return endpointMetadata.apiCodeByApiCodeView(); // 클래스1의 Map을 그대로 노출한다(불변 Map).
    }

    public Map<String, String> apiUrlByApiCodeView() { // URL Map의 읽기 전용 뷰를 반환한다.
        return endpointMetadata.apiUrlByApiCodeView(); // 클래스1의 Map을 그대로 노출한다(불변 Map).
    }

    public Map<String, String> httpMethodByApiCodeView() { // HTTP 메서드 Map의 읽기 전용 뷰를 반환한다.
        return endpointMetadata.httpMethodByApiCodeView(); // 클래스1의 Map을 그대로 노출한다(불변 Map).
    }

    public int size() { // 스냅샷에 들어있는 API 코드 수를 반환한다.
        return endpointMetadata.size(); // 기존 정의대로 클래스1의 크기를 반환한다.
    }

    public String getApiCodeOrNull(String apiCode) { // API 코드가 존재하는지/정규화된 값을 얻고 싶을 때 사용한다.
        return endpointMetadata.getApiCodeOrNull(apiCode); // 기존 호출부 호환을 위해 클래스1에서 조회한다.
    }

    public String getApiUrlOrNull(String apiCode) { // API 코드로 URL을 조회한다.
        return endpointMetadata.getApiUrlOrNull(apiCode); // 기존 호출부 호환을 위해 클래스1에서 조회한다.
    }

    public String getHttpMethodOrNull(String apiCode) { // API 코드로 HTTP 메서드를 조회한다.
        return endpointMetadata.getHttpMethodOrNull(apiCode); // 기존 호출부 호환을 위해 클래스1에서 조회한다.
    }

    @Override
    public boolean equals(Object o) { // Map 값 비교/테스트 등에서 사용할 동등성 기준을 정의한다.
        if (this == o) { // 같은 인스턴스면 항상 같다.
            return true; // 빠른 경로로 종료한다.
        }
        if (!(o instanceof ApiCommonMeta)) { // 타입이 다르면 같을 수 없다.
            return false; // 다른 타입이므로 false를 반환한다.
        }
        ApiCommonMeta that = (ApiCommonMeta) o; // 타입이 같으므로 캐스팅한다.
        return endpointMetadata.equals(that.endpointMetadata) // 클래스1이 같고
                && endpointRouting.equals(that.endpointRouting); // 클래스2가 같으면 동일로 본다.
    }

    @Override
    public int hashCode() { // 해시 기반 컬렉션에서 사용할 해시 코드를 정의한다.
        return Objects.hash(endpointMetadata, endpointRouting); // 클래스1/클래스2 조합으로 해시를 만든다.
    }

    @Override
    public String toString() { // 로그/디버깅에서 보기 쉬운 문자열 표현을 만든다.
        return "ApiCommonMeta{" // 클래스명과
                + "endpointMetadataSize=" + endpointMetadata.size() // 클래스1 크기와
                + ", endpointRoutingSize=" + endpointRouting.size() // 클래스2 크기와
                + '}'; // 객체 형태로 마무리한다.
    }

}
