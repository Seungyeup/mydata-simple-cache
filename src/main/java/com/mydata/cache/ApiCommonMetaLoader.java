package com.mydata.cache; // 패키지 경로를 선언한다.



/**
 * DB에서 API 키 -> 엔드포인트 정보를 한 번에 읽어와 스냅샷으로 제공하는 로더 인터페이스이다.
 *
 * 구현체에서 "dbio"(당신의 DB 접근 계층)를 사용해 전체 목록을 가져오면 된다.
 */
public interface ApiCommonMetaLoader { // 캐시는 DB 접근을 직접 알 필요가 없도록 인터페이스로 분리한다.

    ApiCommonMeta loadSnapshot(ApiCaches.DMdcCacheMng dMdcCacheMng, ApiCaches.Input input); // 호출 시점에 dbio/input을 받아 전체 스냅샷을 만든다.
}
