# JVM Cache 인수인계 문서

## 1. 이 문서의 목적

이 문서는 이 저장소의 JVM 메모리 캐시가 **어떻게 동작하는지**, 그리고 **어떤 점이 개선된 구조인지**를 주니어 개발자가 빠르게 이해할 수 있도록 설명하는 인수인계 문서다.

핵심만 먼저 말하면, 이 캐시는 **정적 싱글톤 진입점 + 불변 스냅샷 + 원자적 참조 교체** 방식으로 동작한다.

- 정적 싱글톤: 앱 안에서 캐시 인스턴스를 하나만 유지
- 불변 스냅샷: 조회 시점에는 변경되지 않는 완성본 객체를 읽음
- 원자적 참조 교체: 갱신 시 기존 객체를 부분 수정하지 않고 새 객체를 통째로 바꿔 끼움

이 구조 덕분에 **조회는 빠르고**, **리로드 중에도 중간 상태가 노출되지 않으며**, **리로드 실패 시 마지막 정상 스냅샷을 유지**할 수 있다.

---

## 2. 먼저 알아야 할 결론

### 현재 구조를 한 줄로 요약하면

> `ApiCaches`가 캐시 인스턴스를 한 번만 만들고, `ApiCommonMetaCache`는 내부에 들고 있는 `AtomicReference<ApiCommonMeta>`를 통해 현재 스냅샷을 읽거나 새 스냅샷으로 교체한다.

### 조회 시 중요한 점

평소 조회는 `synchronized` 블록 안에서 Map을 직접 읽는 방식이 아니다. 이미 만들어진 스냅샷 객체를 `snapshotRef.get()`으로 가져와 읽는다.

즉, **읽기 경로에서는 큰 락 경쟁이 없다.**

### 갱신 시 중요한 점

갱신은 기존 Map을 부분 수정하지 않는다.

1. DB에서 전체 데이터를 다시 읽고
2. 새 `ApiCommonMeta`를 만들고
3. 내부 Map들을 sanitize/freeze 하고
4. 마지막에 `snapshotRef.set(newSnapshot)`으로 한 번에 교체한다.

즉, 호출자는 항상 아래 둘 중 하나만 본다.

- 이전 완성본
- 새 완성본

반쯤 갱신된 중간 상태는 보지 않는다.

---

## 3. 핵심 파일 지도

경로보다는 **파일별 특징** 위주로 기억하는 편이 인수인계에 더 도움이 된다.

| 파일 | 특징 | 주니어가 볼 때 포인트 |
|---|---|---|
| `ApiCaches.java` | 정적 진입점 + lazy-init 싱글톤 관리 | 캐시 객체는 여기서 한 번만 만들어진다. |
| `ApiCommonMetaCache.java` | 캐시 핵심 엔진 | TTL, 리로드 락, 스냅샷 교체, 실패 정책이 모두 여기에 있다. |
| `ApiCommonMetaLoader.java` | 캐시와 DB 접근을 분리하는 인터페이스 | 캐시가 DB 구현 상세를 직접 몰라도 되게 해준다. |
| `DefaultApiCommonMetaLoader.java` | DB row를 스냅샷으로 조립하는 기본 구현 | row -> Map -> snapshot 변환 흐름을 이해할 때 먼저 보면 좋다. |
| `ApiCommonMeta.java` | 최상위 스냅샷 객체 | 호출부는 보통 이 객체를 통해 URL/메서드를 읽는다. |
| `ApiEndpointMetadataSnapshot.java` | 실제 조회 중심 메타 저장소 | `apiCode/url/httpMethod`와 추가 Set 메타를 불변으로 들고 있다. |
| `ApiEndpointRoutingSnapshot.java` | 라우팅 확장 슬롯 | 현재는 비중이 작지만 구조 확장 포인트다. |
| `FreezeUtils.java` | 공통 불변화 유틸 | 외부 mutable Map 참조가 캐시 안으로 들어오지 않게 막는다. |
| `CacheReloadLogPublisher.java` | 로그 발행 인터페이스 | 운영 환경에서 어떤 로깅/메시징 수단을 붙일지 여기로 추상화한다. |
| `CacheReloadLogSupport.java` | 리로드 로그 JSON 생성과 안전 발행 | 성공/실패, duration, diff를 남기고, 로그 실패가 캐시를 망치지 않게 한다. |
| `NoopCacheReloadLogPublisher.java` | 기본 no-op 퍼블리셔 | 로그 구현이 없어도 캐시가 동작하게 해주는 안전장치다. |
| `ApiEndpointCacheUsageExample.java` | 최소 사용 예제 | 실제 호출 순서를 가장 빠르게 파악할 수 있는 샘플이다. |
| `ApiCachesValidationArchive.java` | 메인 경로에서 빠진 validation 보존 코드 | 현재 활성 구조와 과거 확장 흔적을 비교할 때 참고한다. |

---

## 4. 전체 동작 흐름

### 4-1. 최초 초기화 흐름

시작은 `ApiCaches.apiEndpointCache(dMdcCacheMng, input)` 이다.

동작 순서는 아래와 같다.

1. `API_ENDPOINT_CACHE_REF.get()`으로 기존 캐시가 있는지 확인
2. 없으면 `synchronized (API_ENDPOINT_INIT_LOCK)` 진입
3. 락 안에서 다시 한 번 캐시 존재 여부 확인
4. `DefaultApiCommonMetaLoader` 생성
5. `ApiCommonMetaCache` 생성
6. `cache.reloadOrThrow(dMdcCacheMng, input)` 수행
7. 최초 스냅샷이 성공적으로 적재되면 `API_ENDPOINT_CACHE_REF.set(cache)`
8. 이후부터는 같은 캐시 인스턴스를 재사용

핵심 포인트는 두 가지다.

- `synchronized`는 **최초 1회 캐시 객체 생성**에만 사용된다.
- 캐시 인스턴스가 만들어진 이후 평소 조회 경로는 여기 락을 다시 타지 않는다.

---

### 4-2. 평소 조회 흐름

대표 메서드는 아래다.

- `getApiUrlOrNull(String apiCode)`
- `getHttpMethodOrNull(String apiCode)`
- `snapshot()`

이 메서드들은 모두 사실상 아래 패턴이다.

1. `snapshotRef.get()`으로 현재 스냅샷 읽기
2. 스냅샷 내부 불변 Map에서 값 조회

즉, 조회 자체는 매우 단순하다.

### 왜 빠른가?

이 경로에는 아래가 없다.

- DB 호출
- 전체 재계산
- 공유 mutable Map 수정
- 무거운 synchronized 블로킹

그래서 읽기 비율이 높은 서비스에서 유리하다.

---

### 4-3. TTL 기반 자동 리로드 흐름

`snapshot(dMdcCacheMng, input)` 또는 TTL 지원 버전의 조회 메서드는 내부에서 `tryReloadIfExpired(...)`를 호출한다.

### 동작 순서

1. `lastSuccessfulReloadEpochMillis` 확인
2. 현재 시각과 비교해서 TTL(5분) 만료 여부 판단
3. TTL이 아직 유효하면 즉시 기존 스냅샷 반환
4. TTL이 만료됐으면 `reloadLock.tryLock()` 시도
5. 락을 얻은 스레드만 실제 리로드 수행
6. 락을 못 얻은 스레드는 대기하지 않고 기존 스냅샷 사용

### 여기서 중요한 설계 의도

이 캐시는 **정확히 최신 데이터만 보장하는 캐시**보다, **서비스가 멈추지 않는 캐시**를 더 우선한다.

즉:

- 잠깐 stale 한 데이터는 허용
- 대신 모든 요청이 리로드 대기로 막히는 상황은 피함

이건 실무에서 꽤 좋은 선택이다. 읽기 트래픽이 많은 캐시는 보통 이 방향이 더 안정적이다.

---

### 4-4. 실제 리로드 흐름

리로드는 `reloadOrThrow(...)`, `tryReload(...)`, `tryReloadIfExpired(...)` 세 경로가 있지만 핵심은 같다.

### 공통 흐름

1. 이전 스냅샷(`prev`) 확보
2. 로더가 DB에서 전체 메타를 읽음
3. `sanitizeAndFreeze(loaded)` 호출
4. 완성된 새 스냅샷(`next`) 생성
5. `snapshotRef.set(next)`로 교체
6. 성공 시간 기록
7. diff 계산 후 로그 발행

### 왜 부분 수정이 아니라 전체 교체인가?

부분 수정 방식은 아래 위험이 있다.

- 여러 Map 중 일부만 먼저 바뀌는 중간 상태 노출 가능
- 동시성 제어가 복잡해짐
- 실패 복구가 어려움

현재 방식은 새 객체를 완전히 만든 뒤 교체하므로 구조가 훨씬 단순하다.

---

## 5. 스냅샷 데이터 구조를 이해해야 하는 이유

이 캐시를 이해할 때 가장 중요한 개념은 **캐시는 Map 자체가 아니라 스냅샷 객체**라는 점이다.

### 최상위 스냅샷

`ApiCommonMeta`

- `ApiEndpointMetadataSnapshot endpointMetadata`
- `ApiEndpointRoutingSnapshot endpointRouting`

### 실사용 조회는 어디를 보나?

현재 `getApiUrlOrNull`, `getHttpMethodOrNull`은 주로 `endpointMetadata`를 통해 조회한다.

즉, 지금 이 예제 구조에서 실질적인 조회 중심 데이터는 `ApiEndpointMetadataSnapshot` 쪽이다.

### 왜 구조를 둘로 나눴나?

의도를 보면 다음과 같다.

- `endpointMetadata`: API 메타 본체
- `endpointRouting`: 라우팅 성격 데이터 확장 지점

현재 예제에서는 `endpointRouting`이 거의 비어 있지만, 구조상 확장 슬롯으로 남겨둔 형태다.

---

## 6. sanitizeAndFreeze 가 핵심인 이유

실무에서 캐시 버그는 조회 코드보다 **캐시에 들어가기 직전 정제 단계**에서 많이 난다. 이 코드에서는 그 역할을 `sanitizeAndFreeze(...)`가 맡는다.

### 이 메서드가 하는 일

1. `null` 스냅샷 방지
2. 비어 있는 값 정리
3. 잘못된 key/value 제거
4. Map key 집합 정규화
5. `Map<String, Set<String>>`도 내부 Set까지 방어적 복사
6. 최종적으로 불변 Map/Set으로 고정

### 주니어가 꼭 이해해야 할 포인트

캐시에 들어간 뒤에는 데이터가 절대 바뀌면 안 된다.

그래서:

- 생성 전에 정제하고
- 생성하면서 복사하고
- 생성 후에는 수정 불가능하게 막는다

이 순서가 매우 중요하다.

### 방어적 복사의 의미

예를 들어 로더가 만든 Map을 그대로 들고 있으면, 다른 코드가 그 Map 참조를 잡고 나중에 수정할 수 있다. 그러면 캐시가 불변이라는 가정이 깨진다.

그래서 `FreezeUtils.freezeStringMap(...)`와 `freezeStringSetMap(...)`으로 복사 후 불변화한다.

---

## 7. 실패 처리 방식

이 캐시는 리로드 실패 시 정책이 명확하다.

### `reloadOrThrow(...)`

- 주로 최초 로딩 또는 반드시 성공해야 하는 수동 리로드에 사용
- 실패하면 예외를 던짐
- 스타트업 실패를 강하게 드러내고 싶을 때 적합

### `tryReload(...)`

- 실패해도 예외 대신 `false` 반환
- 기존 스냅샷은 유지

### `tryReloadIfExpired(...)`

- TTL 만료 시 자동 리로드 시도
- 실패해도 예외를 호출자에게 던지지 않음
- 기존 스냅샷 유지

### 실무적으로 좋은 이유

운영 중 DB가 잠깐 흔들릴 수 있다. 그때 매번 캐시 조회 요청까지 같이 죽이면 서비스가 훨씬 불안정해진다.

현재 구조는 **마지막 성공 스냅샷으로 버틴다**는 점에서 운영 안정성이 좋다.

---

## 8. 동시성 설계 포인트

### 1) 초기화 락과 리로드 락을 분리했다

- 초기화: `API_ENDPOINT_INIT_LOCK`
- 리로드: `reloadLock`

즉, “캐시 객체를 만드는 문제”와 “캐시 데이터를 다시 적재하는 문제”를 분리했다.

이건 좋은 설계다. 락 책임이 섞이지 않기 때문이다.

### 2) 리로드는 tryLock 기반이다

`reloadLock.tryLock()`에 실패한 스레드는 기다리지 않고 기존 스냅샷을 사용한다.

이 덕분에 리로드 폭주나 대기열 증가를 줄일 수 있다.

### 3) volatile timestamp 사용

`lastSuccessfulReloadEpochMillis`는 `volatile`이라 여러 스레드가 최신 성공 시각을 볼 수 있다.

### 4) double-check 패턴 사용

TTL 검사도 락 전/락 후 두 번 본다.

이유는 간단하다.

- 락을 기다리는 사이 이미 다른 스레드가 리로드를 끝냈을 수 있기 때문

그래서 중복 리로드를 줄인다.

---

## 9. 로더가 실제로 하는 일

`DefaultApiCommonMetaLoader.loadSnapshot(...)`의 역할은 아주 단순하다.

1. `dMdcCacheMng.selectApiMetaInfoList00(input.getApiCode())` 호출
2. DB 결과 row 목록을 순회
3. `apiCode -> apiCode`, `apiCode -> url`, `apiCode -> method` Map 구성
4. 예시용 `stringSetMap` 구성
5. `ApiEndpointMetadataSnapshot` 생성
6. `ApiCommonMeta` 반환

### 주의할 점

현재 예제 코드에서는 `Input.versionOrNull`이 실제 로더 조회에 사용되지 않는다.

즉, 이 저장소 기준 현재 구현은 **버전별 캐시 키 분리**까지는 하지 않고 있다. 나중에 확장할 때 주의해야 한다.

---

## 10. 로그 설계 포인트

리로드 때 로그를 남기는 이유는 운영에서 매우 중요하다.

`CacheReloadLogSupport.toJson(...)`는 아래 정보를 남긴다.

- cache 이름
- reload 타입
- success 여부
- startedAtEpochMillis
- durationMillis
- size
- added / removed / changed diff
- errorClass / errorMessage

### 좋은 점

- 운영 중 리로드 빈도 파악 가능
- 리로드 시간이 느려졌는지 감지 가능
- 데이터 증감 추적 가능
- 실패 원인 추적 가능

### 또 하나 중요한 점

`publishSafely(...)`는 로그 발행 실패를 삼켜 버린다. 즉, **로그 시스템 장애가 캐시 본동작을 깨지 않게** 설계했다.

운영 안정성 관점에서 합리적인 선택이다.

---

## 11. “무엇이 개선됐는가”를 정확히 말하는 방법

이 부분은 인수인계 때 가장 조심해야 한다.

### 11-1. 설계 관점에서의 개선

README와 현재 코드 설명 기준으로, 이 캐시가 해결하려는 문제는 아래다.

### 이전 방식의 문제 의식

- 정적 공유 상태에 직접 접근
- 공유 mutable Map을 여러 스레드가 읽고/갱신
- 조회 경로까지 넓은 동기화가 걸릴 수 있음
- 갱신 중 중간 상태 노출 위험
- 실패 시 상태 관리 복잡

### 현재 방식의 개선점

1. **조회 락 축소**
   - 평소 조회는 `snapshotRef.get()` 중심
   - 조회 경로 락 경쟁 감소

2. **중간 상태 차단**
   - 기존 객체 부분 수정 대신 새 스냅샷 전체 생성 후 교체

3. **리로드 실패 내성 강화**
   - 실패해도 이전 정상 스냅샷 유지

4. **리로드 중 서비스 연속성 확보**
   - 한 스레드만 리로드
   - 다른 스레드는 기존 데이터 계속 사용

5. **불변 모델로 안전성 향상**
   - 캐시 적재 후 외부 변경 차단

### 11-2. 이 저장소 Git 이력 기준으로 실제 확인되는 개선

여기서는 과장하면 안 된다.

Git 이력을 확인해 보면, **현재의 스냅샷 캐시 런타임 로직 자체는 initial commit 시점부터 이미 존재**한다. 즉, 이 저장소 안에서 “락 기반 구형 캐시 → 스냅샷 캐시”로 바뀌는 diff가 직접 남아 있지는 않다.

대신 현재 HEAD 커밋(`04252db`)에서 실제로 확인되는 개선은 아래다.

1. **구조 정리**
   - `ApiCommonMetaCache`, `ApiCommonMetaLoader`, `DefaultApiCommonMetaLoader`를 `apicache` 패키지로 이동
   - 캐시 책임이 더 명확해짐

2. **명명 개선**
   - `Class1` -> `ApiEndpointMetadataSnapshot`
   - `Class2` -> `ApiEndpointRoutingSnapshot`
   - 도메인 의미가 명확해져서 주니어가 이해하기 쉬워짐

3. **validation 경로 분리**
   - active main path 에서 validation 캐시 관련 로직 제거
   - archive 보관 영역으로 이동
   - 현재 메인 캐시의 관심사가 더 좁아짐

4. **운영/문서 가시성 강화**
   - 클래스 다이어그램 추가
   - 예제 코드 추가
   - 일부 logging helper 접근성 공개화

즉, **설계 개선의 핵심은 스냅샷 캐시 방식 자체**이고, **이 저장소의 최신 커밋에서 직접 보이는 개선은 구조 정리와 명명 개선**이라고 이해하면 가장 정확하다.

---

## 12. 주니어가 수정할 때 절대 놓치면 안 되는 규칙

### 규칙 1. snapshot 내부 Map을 외부에 mutable 하게 노출하지 말 것

캐시 안정성의 핵심은 불변성이다. 새 필드를 추가하더라도 반드시 방어적 복사 + 불변화가 필요하다.

### 규칙 2. 부분 업데이트 방식으로 바꾸지 말 것

기존 Map 일부만 바꾸는 방식은 언뜻 효율적으로 보여도 동시성/일관성 문제가 커진다. 이 캐시는 전체 스냅샷 교체를 전제로 설계되어 있다.

### 규칙 3. 조회 경로에 무거운 작업을 넣지 말 것

`getApiUrlOrNull`, `getHttpMethodOrNull`, `snapshot()` 같은 메서드에 DB 호출, JSON 파싱, 대규모 계산을 넣으면 이 설계의 장점이 사라진다.

### 규칙 4. TTL 정책을 바꿀 때는 stale 허용 범위를 같이 설명할 것

TTL을 줄이면 더 자주 최신화되지만 DB 부하가 늘고, 늘리면 stale window가 커진다. 운영 요구사항과 같이 봐야 한다.

### 규칙 5. 신규 필드를 넣을 때 diff/log도 함께 볼 것

운영 관측성이 중요하면 diff 계산 또는 로그 payload에 새 필드 영향이 있는지 검토해야 한다.

---

## 13. 운영상 트레이드오프

좋은 설계지만 공짜는 아니다.

### 1) stale window 존재

TTL 내에서는 예전 데이터가 유지될 수 있다.

### 2) 리로드 비용은 전체 재구성

변경분만 패치하는 방식이 아니라 전체 스냅샷 재생성이다.

### 3) 교체 순간 메모리 2벌 가능

이전 스냅샷과 새 스냅샷이 잠깐 동시에 존재할 수 있다.

그래도 이 저장소의 설계 방향은 분명하다.

> 읽기가 훨씬 많고 갱신이 드문 캐시라면, 이 정도 비용을 내고라도 단순하고 안전한 구조를 택한다.

---

## 14. 실무에서 다음 개선 후보

주니어가 앞으로 발전 방향을 물어보면 아래 순서로 설명하면 된다.

1. **TTL 외부 설정화**
   - 현재는 `TTL_MILLIS = 5분` 고정
   - 환경설정으로 빼면 운영 유연성이 커짐

2. **버전별 캐시 키 전략 명확화**
   - 현재 `Input.versionOrNull` 활용이 약함
   - 버전별 API 메타가 중요하면 key model 재설계 필요

3. **테스트에서 reset/replace 훅 제공**
   - 정적 싱글톤 구조는 테스트 격리에 불리할 수 있음

4. **메트릭 연동**
   - reload success/failure count
   - reload duration histogram
   - current snapshot size gauge

5. **대용량 데이터 대응 점검**
   - snapshot size 증가 시 메모리 사용량 확인

---

## 15. 최종 요약

이 캐시는 아래 문장으로 기억하면 된다.

> 캐시 객체는 정적으로 하나만 두되, 그 안의 데이터는 mutable shared state로 다루지 않고 불변 스냅샷으로 만들어 원자적으로 교체한다.

주니어 입장에서 가장 중요한 핵심은 세 가지다.

1. **조회는 현재 스냅샷을 읽는 일이다.**
2. **갱신은 새 스냅샷을 만든 뒤 교체하는 일이다.**
3. **실패해도 마지막 정상 스냅샷을 지키는 것이 이 캐시의 운영 철학이다.**

---

## 16. 근거로 확인한 파일

- `ApiCaches.java`
- `ApiCommonMetaCache.java`
- `ApiCommonMetaLoader.java`
- `DefaultApiCommonMetaLoader.java`
- `ApiCommonMeta.java`
- `ApiEndpointMetadataSnapshot.java`
- `ApiEndpointRoutingSnapshot.java`
- `FreezeUtils.java`
- `CacheReloadLogSupport.java`
- `ApiEndpointCacheUsageExample.java`
- `ApiCachesValidationArchive.java`
- `class-diagram.mmd`
- `README.md`

### Git 근거

- `d9e153c` `Initial commit`
- `04252db` `.`

위 두 커밋 비교 결과를 기준으로 개선 내용을 정리했다.
