package com.mydata.cache; // 패키지 경로를 선언한다.

import com.mydata.cache.apicache.ApiCommonMetaCache;
import com.mydata.cache.apicache.ApiCommonMetaLoader;
import com.mydata.cache.apicache.DefaultApiCommonMetaLoader;

import java.util.List; // DBIO가 반환하는 목록 타입을 사용한다.
import java.util.concurrent.atomic.AtomicReference; // 싱글톤 내용물을 원자적으로 교체하기 위해 사용한다.

/**
 * main(또는 프레임워크 진입점)에서 캐시를 직접 생성/선언하기 어렵다면,
 * 이처럼 별도의 정적 클래스에서 "부팅 시점"에 캐시를 만들어두고
 * 이후에는 어디서든 동일 인스턴스를 꺼내 쓰는 패턴을 쓴다.
 */
public final class ApiCaches { // 유틸리티 성격이므로 final로 고정한다.

    // "싱글톤 인스턴스는 static"으로 유지하되, 내부 내용물(cache)은 최초 거래 시점에 늦게(lazy) 만든다.
    private static final AtomicReference<ApiCommonMetaCache> API_ENDPOINT_CACHE_REF = new AtomicReference<ApiCommonMetaCache>(); // 실제 캐시 인스턴스는 여기에 들어간다.
    private static final Object API_ENDPOINT_INIT_LOCK = new Object(); // 최초 1회 초기화를 동기화하기 위한 락 객체다.

    /*
     * Validation integration was moved to archive/validation-disabled and is no longer active in main.
     * See archive/validation-disabled/src/main/java/com/mydata/cache/ApiCachesValidationArchive.java
     * and archive/validation-disabled/src/main/java/com/mydata/cache/validation for the preserved code.
     */

    private ApiCaches() { // 인스턴스 생성을 막는다.
    }

    public static ApiCommonMetaCache apiEndpointCache() { // 이미 만들어진 캐시를 반환한다.
        ApiCommonMetaCache cache = API_ENDPOINT_CACHE_REF.get(); // 현재 캐시 인스턴스를 읽는다.
        if (cache == null) { // 아직 초기화가 안 됐으면
            throw new IllegalStateException("ApiCaches.apiEndpointCache is not initialized yet"); // 초기화 순서 문제를 빠르게 드러낸다.
        }
        return cache; // 초기화된 캐시를 반환한다.
    }

    public static ApiCommonMetaCache apiEndpointCache(DMdcCacheMng dMdcCacheMng, Input input) { // 최초 거래 시점에 주입받은 dbio로 캐시를 초기화한다.
        return apiEndpointCache(dMdcCacheMng, input, null); // reload 로그 퍼블리셔가 없으면 null로 둔다(no-op 처리).
    }

    public static ApiCommonMetaCache apiEndpointCache(DMdcCacheMng dMdcCacheMng, Input input, CacheReloadLogPublisher reloadLogPublisher) { // API 엔드포인트 캐시를 생성/반환한다.
        ApiCommonMetaCache existing = API_ENDPOINT_CACHE_REF.get(); // 이미 초기화된 캐시가 있는지 확인한다.
        if (existing != null) { // 이미 있으면
            return existing; // 그대로 반환한다(추가 초기화 없음).
        }

        synchronized (API_ENDPOINT_INIT_LOCK) { // 초기화가 필요하면 1회만 수행되도록 동기화한다.
            ApiCommonMetaCache again = API_ENDPOINT_CACHE_REF.get(); // 락 안에서 다시 확인한다(경쟁 상태 방지).
            if (again != null) { // 누군가 먼저 초기화했으면
                return again; // 그 인스턴스를 반환한다.
            }

            if (dMdcCacheMng == null) { // dbio가 없으면
                throw new IllegalArgumentException("dMdcCacheMng must not be null"); // 캐시를 만들 수 없으므로 실패시킨다.
            }
            if (input == null) { // input이 없으면
                throw new IllegalArgumentException("input must not be null"); // 호출 계약을 명확히 한다.
            }

            ApiCommonMetaLoader loader = new DefaultApiCommonMetaLoader(); // 무상태 기본 로더.
            ApiCommonMetaCache cache = new ApiCommonMetaCache(loader, (reloadLogPublisher == null ? NoopCacheReloadLogPublisher.INSTANCE : reloadLogPublisher));
            cache.reloadOrThrow(dMdcCacheMng, input); // 최초 1회 로딩(스냅샷 생성).

            API_ENDPOINT_CACHE_REF.set(cache); // static 싱글톤 내용물(진짜 캐시)을 채워 넣는다.
            return cache; // 초기화된 캐시를 반환한다.
        }
    }

    // 아래 타입들은 "예제"를 컴파일/실행 가능하게 만들기 위한 최소 형태다.
    // 실제 프로젝트에서는 당신의 실제 타입(DMdcCacheMng/input/dbioDto)을 그대로 사용하면 된다.
    public interface DMdcCacheMng { // 당신이 말한 dMdcCacheMng의 최소 인터페이스 형태다.
        List<DbioDto> selectApiMetaInfoList00(String apiCode); // DB에서 API 메타 정보를 조회한다.
    }

    public static final class Input { // 당신이 말한 input의 최소 형태다.
        private final String versionOrNull;
        private final String apiCode; // 조회 조건(전체면 null/blank)을 가진다.

        public Input(String apiCode) { // 생성자에서
            this(null, apiCode);
        }

        public Input(String versionOrNull, String apiCode) {
            this.versionOrNull = versionOrNull;
            this.apiCode = apiCode;
        }

        public String getVersionOrNull() {
            return versionOrNull;
        }

        public String getApiCode() { // 당신이 준 메서드명(getApiCode)을 그대로 둔다.
            return apiCode; // 값을 반환한다.
        }
    }

    public static final class DbioDto { // 당신이 말한 dbioDto의 최소 형태(예제)다.
        private final String apiCode; // API 키(예: BA01)
        private final String apiUrl; // URL
        private final String httpMethod; // HTTP 메서드

        public DbioDto(String apiCode, String apiUrl, String httpMethod) { // 생성자에서
            this.apiCode = apiCode; // 값을 저장한다.
            this.apiUrl = apiUrl; // 값을 저장한다.
            this.httpMethod = httpMethod; // 값을 저장한다.
        }

        public String getApiCode() { // 당신이 말한 getter 이름을 그대로 둔다.
            return apiCode; // 값을 반환한다.
        }

        public String getApiUrl() { // 당신이 말한 getter 이름을 그대로 둔다.
            return apiUrl; // 값을 반환한다.
        }

        public String getHttpMethod() { // 당신이 말한 getter 이름을 그대로 둔다.
            return httpMethod; // 값을 반환한다.
        }
    }
}
