# Snapshot Cache 핵심 로직 인수인계 문서

> 대상: 이 캐시를 처음 맡는 주니어 개발자
> 목적: "이 캐시가 **왜 snapshot(스냅샷) 방식**이고, **핵심 로직이 코드 어디에 어떻게** 박혀 있는지"를 한 번에 이해시키기
> 범위: 핵심 파일 `src/main/java/com/mydata/cache/apicache/ApiCommonMetaCache.java` 를 중심으로, 스냅샷 모델 클래스까지.

---

## 0. 한 문장 요약

> **캐시는 `Map`이 아니라 "불변 스냅샷 객체 1개"다. 조회는 그 스냅샷을 락 없이 읽고, 갱신은 새 스냅샷을 통째로 만들어 `AtomicReference`로 한 번에 갈아끼운다.**

이 한 문장에 이 캐시의 모든 설계가 들어있다. 아래는 그걸 코드로 풀어 설명하는 것이다.

---

## 1. "스냅샷 캐시"가 정확히 무슨 뜻인가

보통 사람들이 떠올리는 캐시는 이런 모양이다.

```java
// 흔한(그러나 이 저장소가 일부러 피한) 방식
private final Map<String, String> urlMap = new ConcurrentHashMap<>();
// 조회: urlMap.get(code)
// 갱신: urlMap.put(code, url)  ← 기존 Map을 "부분 수정"
```

이 방식의 문제는 **"읽는 도중에 누가 Map을 고친다"** 는 점이다. 갱신이 절반쯤 진행된 상태(일부 키는 새 값, 일부는 옛 값)가 조회에 노출될 수 있다.

이 저장소는 정반대로 간다.

```java
// 이 저장소의 스냅샷 방식
private final AtomicReference<ApiCommonMeta> snapshotRef; // "완성된 데이터 묶음" 하나를 가리키는 참조
// 조회: snapshotRef.get() 으로 완성본을 통째로 받아서 읽음
// 갱신: 새 완성본을 따로 만든 뒤 snapshotRef.set(새완성본) 으로 참조만 교체
```

- **스냅샷(snapshot)** = 특정 시점의 데이터 전체를 담은, 한번 만들면 절대 안 바뀌는 **불변(immutable) 객체**.
- 갱신은 기존 스냅샷을 *고치는* 게 아니라, **새 스냅샷으로 통째로 바꿔치기(swap)** 한다.
- 그래서 조회하는 쪽은 **항상 "옛 완성본" 아니면 "새 완성본"** 만 본다. **반쯤 바뀐 중간 상태는 구조적으로 존재할 수 없다.**

> 비유: 갤러리 벽에 걸린 그림을 덧칠하는 게 아니라(부분 수정), 새 그림을 다 그린 뒤 액자만 바꿔 거는 것(스냅샷 교체). 관람객은 항상 완성된 그림만 본다.

---

## 2. 데이터 구조 — 캐시가 들고 있는 "스냅샷"의 정체

핵심: **`ApiCommonMetaCache`가 들고 있는 건 `Map`이 아니라 `ApiCommonMeta`라는 스냅샷 객체 1개**다.

```
ApiCommonMetaCache
└── AtomicReference<ApiCommonMeta> snapshotRef   ← 현재 스냅샷을 가리키는 단 하나의 참조
        │
        └── ApiCommonMeta (불변)                  ← "완성본" 그 자체
                ├── ApiEndpointMetadataSnapshot  endpointMetadata  ← 실제 조회 대상
                │     ├── Map<String,String> apiCodeByApiCode   (불변)
                │     ├── Map<String,String> apiUrlByApiCode     (불변)  ← getApiUrlOrNull이 여기서 읽음
                │     ├── Map<String,String> httpMethodByApiCode (불변)  ← getHttpMethodOrNull이 여기서 읽음
                │     └── Map<String,Set<String>> stringSetByApiCode (딥 불변)
                └── ApiEndpointRoutingSnapshot   endpointRouting   ← 확장용 슬롯(현재 거의 비어 있음)
```

주니어가 기억할 포인트:
- 실제 URL/메서드 조회는 전부 **`endpointMetadata`(=`ApiEndpointMetadataSnapshot`)** 안의 Map에서 일어난다.
- `endpointRouting`은 지금은 거의 빈 채로 두는 **미래 확장 슬롯**이다.
- 이 객체들은 전부 `final` 필드 + 불변 Map으로 만들어져 **생성 후 절대 안 바뀐다.** (3장에서 설명)

---

## 3. 핵심 로직 ① — 불변성(immutability)은 어떻게 보장되나 (`sanitizeAndFreeze`)

스냅샷 캐시의 안전성은 **"한번 캐시에 들어간 데이터는 절대 안 바뀐다"** 는 약속에서 나온다.
이 약속을 강제하는 곳이 `sanitizeAndFreeze(...)` → 각 스냅샷 생성자 → `FreezeUtils` 로 이어지는 3단 방어다.

### 왜 필요한가 — "참조를 그냥 들고 있으면" 생기는 버그

```java
// 위험한 코드 (이렇게 하면 안 됨)
Map<String,String> loaded = loader가 만든 Map;
this.urlMap = loaded;   // ← loaded를 만든 쪽이 나중에 loaded.put(...) 하면 캐시가 몰래 바뀐다!
```

외부가 들고 있는 Map 참조를 그대로 저장하면, 그 외부 코드가 나중에 Map을 수정했을 때 **캐시가 의도치 않게 변한다.** 불변성 약속이 깨진다.

### 방어 1단계 — `sanitizeAndFreeze`: 정제

리로드가 DB에서 읽은 결과(`loaded`)를 캐시에 넣기 직전에 거치는 관문이다.

```java
private static ApiCommonMeta sanitizeAndFreeze(ApiCommonMeta loaded) {
    if (loaded == null) {
        return ApiCommonMeta.empty();              // null은 절대 저장 안 함 → 빈 스냅샷으로
    }
    ApiEndpointMetadataSnapshot endpointMetadata = sanitizeApiEndpointMetadataSnapshot(loaded.endpointMetadata());
    ApiEndpointRoutingSnapshot  endpointRouting  = sanitizeApiEndpointRoutingSnapshot(loaded.endpointRouting());
    if (endpointMetadata.size() == 0 && endpointRouting.size() == 0) {
        return ApiCommonMeta.empty();              // 알맹이 없으면 빈 스냅샷
    }
    return new ApiCommonMeta(endpointMetadata, endpointRouting);
}
```

`sanitize...` 내부에서 하는 일(요지):
- `null`/빈 문자열 key·value 제거
- 3개 Map의 key 집합을 **합집합으로 정규화**(`apiCode`/`url`/`method` 키를 맞춤)
- `Map<String,Set<String>>`은 내부 **Set 원소까지** 정리

### 방어 2단계 — 스냅샷 생성자: 방어적 복사

`new ApiEndpointMetadataSnapshot(...)` 생성자가 받은 Map을 **그대로 저장하지 않고 복사해서 불변화**한다.

```java
// ApiEndpointMetadataSnapshot 생성자
this.apiUrlByApiCode = FreezeUtils.freezeStringMap(apiUrlByApiCode);   // 복사 + 불변
this.stringSetByApiCode = freezeStringSetMap(stringSetByApiCode);      // Map+Set 둘 다 복사 + 불변 (딥 프리즈)
```

### 방어 3단계 — `FreezeUtils`: 불변 래핑

```java
static Map<String, String> freezeStringMap(Map<String, String> source) {
    if (source == null || source.isEmpty()) {
        return Collections.<String, String>emptyMap();
    }
    return Collections.unmodifiableMap(new HashMap<String, String>(source)); // ★새 HashMap으로 복사★ 후 ★불변 래핑★
}
```

핵심: `new HashMap<>(source)` 로 **복사본**을 만든 뒤 `unmodifiableMap`으로 감싼다.
→ 원본(`source`)이 나중에 바뀌어도 캐시 안의 복사본은 영향 없고, 캐시 밖에서 `put`을 시도하면 예외가 난다.

> **3단 방어 한 줄 요약: "정제(sanitize) → 복사(copy) → 동결(freeze)". 이 순서를 깨면 불변성이 무너진다.**

---

## 4. 핵심 로직 ② — 조회 경로 (락 없는 O(1) 읽기)

조회는 이 캐시에서 가장 단순하고 가장 자주 일어나는 경로다.

```java
public String getApiUrlOrNull(String apiCode) {
    return snapshotRef.get().getApiUrlOrNull(apiCode);  // 1) 현재 스냅샷 참조를 받고  2) 그 안의 불변 Map에서 get
}
```

- `snapshotRef.get()` 은 **현재 완성본을 가리키는 참조 하나를 읽는 것**뿐이다. 락도, DB도, 재계산도 없다.
- 받아온 스냅샷은 불변이므로, 그 사이 다른 스레드가 리로드로 `set`을 해도 **내가 읽던 완성본은 그대로** 유지된다. (그 스레드는 *다른* 새 객체를 가리키게 만들 뿐, 내가 든 객체를 고치지 않는다.)

### 조회 메서드는 두 종류다 — 차이를 반드시 구분할 것

| 메서드 시그니처 | 동작 | 언제 쓰나 |
|---|---|---|
| `getApiUrlOrNull(String apiCode)` | **그냥 현재 스냅샷만 읽음.** 리로드 트리거 안 함 | 대부분의 일반 조회 |
| `getApiUrlOrNull(DMdcCacheMng, Input, String apiCode)` | **읽기 전에 TTL 만료면 자동 리로드 시도**(`tryReloadIfExpired`) 후 읽음 | 데이터 신선도가 중요한 진입점 |

> 파라미터 없는 버전은 절대 DB를 치지 않는다. `dMdcCacheMng`를 넘기는 버전만 자동 갱신을 트리거한다. (5장)

---

## 5. 핵심 로직 ③ — 갱신(리로드): 스냅샷 교체

갱신의 본질은 **"새 스냅샷을 만들고 `snapshotRef.set`으로 갈아끼우기"** 다. 진입점이 3개 있지만 **알맹이는 동일**하다.

### 공통 알맹이 (3개 메서드 전부 동일)

```java
ApiCommonMeta prev   = snapshotRef.get();                  // 1) 이전 스냅샷 확보 (diff/로그용)
ApiCommonMeta loaded = loader.loadSnapshot(dMdcCacheMng, input); // 2) DB에서 전체를 다시 읽음
ApiCommonMeta next   = sanitizeAndFreeze(loaded);          // 3) 정제+동결 (3장)
snapshotRef.set(next);                                     // 4) ★원자적 교체★ — 이 한 줄이 갱신의 핵심
lastSuccessfulReloadEpochMillis = System.currentTimeMillis(); // 5) 성공 시각 기록 (TTL 기준)
// 6) prev vs next diff 계산 후 로그 발행
```

**4번 `snapshotRef.set(next)` 이 가장 중요하다.** DB 조회·정제가 모두 끝나 *완성된* `next`가 준비된 뒤에야, 마지막에 단 한 줄로 참조를 바꾼다. 그래서 조회 쪽은 교체 직전엔 `prev`, 직후엔 `next`만 보고 **중간 상태를 못 본다.**

### 진입점 3개의 차이 = "실패했을 때 어떻게 할 것인가"

| 메서드 | 호출 시점 | 락 | 실패 시 |
|---|---|---|---|
| `reloadOrThrow(...)` | **최초 부팅 1회** (`ApiCaches`가 호출) | `lock()` (대기) | **예외를 던짐** — 부팅을 실패시킴 |
| `tryReload(...)` | 운영 중 **수동** 강제 리로드 | `lock()` (대기) | `false` 반환, **기존 스냅샷 유지** |
| `tryReloadIfExpired(...)` | TTL 만료 시 **자동** | `tryLock()` (대기 안 함) | 예외 삼킴, **기존 스냅샷 유지** |

#### 왜 부팅(`reloadOrThrow`)만 예외를 던지나?
부팅 땐 폴백할 "지난 정상 데이터"가 없다. 빈 캐시로 서비스가 뜨면 전부 오작동하므로, **차라리 부팅을 실패시켜** 배포를 멈추는 게 안전하다.

```java
// reloadOrThrow: 실패를 숨기지 않는다
} catch (RuntimeException e) {
    ... // 실패 로그
    throw e;  // 그대로 위로 던져 부팅 실패를 드러냄
}
```

#### 왜 운영 중(`tryReload`/`tryReloadIfExpired`)에는 실패를 삼키나?
운영 중 DB가 잠깐 흔들렸다고 조회까지 같이 죽이면 안 된다. **`set(next)`는 try의 맨 끝에서 성공했을 때만** 호출되므로, 중간에 터지면 `snapshotRef`는 건드려지지 않고 **마지막 정상 스냅샷이 그대로 살아있다.**

```java
// tryReload: 실패해도 서비스는 계속
} catch (Exception e) {
    ... // 실패 로그
    return false;   // 기존 스냅샷 유지한 채 "실패했다"만 알림
}
```

> 운영 철학 한 줄: **"잠깐 stale(오래된)한 데이터는 허용한다. 단, 서비스가 멈추는 건 허용하지 않는다."**

---

## 6. 핵심 로직 ④ — TTL 자동 리로드와 동시성 (`tryReloadIfExpired`)

TTL(5분) 만료 시 "조회하다가 슬쩍" 갱신을 트리거하는 부분. **스케줄러 없이** 조회 트래픽에 얹어서 갱신한다. 동시성 처리가 가장 정교한 곳이다.

```java
private void tryReloadIfExpired(ApiCaches.DMdcCacheMng dMdcCacheMng, ApiCaches.Input input) {

    long last = lastSuccessfulReloadEpochMillis;
    long now  = System.currentTimeMillis();

    // (A) TTL 1차 검사: 아직 안 지났으면 락도 안 건드리고 즉시 종료 (평상시 경로 — 비용 0에 가까움)
    if (last != 0L && (now - last) < TTL_MILLIS) {
        return;
    }

    // (B) tryLock: 못 잡으면(=다른 스레드가 이미 리로드 중이면) 대기하지 않고 빠진다
    if (!reloadLock.tryLock()) {
        return;   // → 이 스레드는 기존(약간 stale) 스냅샷을 0ms에 그대로 사용
    }

    try {
        // (C) TTL 2차 검사(더블 체크): 락 기다리는 사이 다른 스레드가 이미 갱신했을 수 있다
        long lastAfterLock = lastSuccessfulReloadEpochMillis;
        long nowAfterLock  = System.currentTimeMillis();
        if (lastAfterLock != 0L && (nowAfterLock - lastAfterLock) < TTL_MILLIS) {
            return;   // 이미 최신 → 중복 리로드 방지
        }

        // (D) 여기서만 실제 DB 접근 + 스냅샷 교체 (5장 공통 알맹이)
        ApiCommonMeta loaded = loader.loadSnapshot(dMdcCacheMng, input);
        ApiCommonMeta next   = sanitizeAndFreeze(loaded);
        snapshotRef.set(next);
        lastSuccessfulReloadEpochMillis = nowAfterLock;
        ...
    } catch (Exception e) {
        ...   // 실패 삼킴 (5장)
    } finally {
        reloadLock.unlock();   // 무조건 해제
        CacheReloadLogSupport.publishSafely(reloadLogPublisher, logMessage);
    }
}
```

### 주니어가 이해해야 할 동시성 4요소

1. **(A) 빠른 경로** — TTL이 유효한 평상시엔 `tryLock`조차 호출하지 않고 즉시 반환. 락 경합이 사실상 0.
2. **(B) `tryLock()` (≠ `lock()`)** — 리로드는 **한 번에 한 스레드만** 한다. 못 잡은 스레드는 *기다리지 않고* 기존 스냅샷으로 즉시 응답한다. → TTL 만료 순간 수백 스레드가 동시에 DB로 몰리는 사고(thundering herd)를 막는다.
3. **(C) 더블 체크** — 락 진입 전(A)·후(C) TTL을 두 번 본다. 락을 막 풀고 나온 직후 스레드가 또 리로드하는 낭비를 막는다.
4. **`volatile lastSuccessfulReloadEpochMillis`** — 한 스레드가 갱신한 성공 시각을 다른 스레드가 **즉시** 보게 해서 (3)이 제대로 동작하게 한다.

### 초기화 락 vs 리로드 락은 분리되어 있다
- **초기화 락**(`ApiCaches.API_ENDPOINT_INIT_LOCK`): 캐시 *객체*를 최초 1회 만드는 문제.
- **리로드 락**(`ApiCommonMetaCache.reloadLock`): 캐시 *데이터*를 다시 적재하는 문제.

둘은 책임이 다르므로 일부러 분리했다. 섞으면 락 책임이 꼬인다.

---

## 7. 전체 생애주기 한눈에 보기

```
[부팅]
ApiCaches.apiEndpointCache(dMdc, input)
  → (init 락) ApiCommonMetaCache 생성
  → reloadOrThrow(...)        ← 첫 스냅샷 적재. 실패하면 부팅 실패(fail-fast)
  → 싱글톤에 보관

[평상시 조회]  (가장 빈번)
getApiUrlOrNull(code)
  → snapshotRef.get().get...  ← 락/DB 없음. O(1)

[TTL 지원 조회]
getApiUrlOrNull(dMdc, input, code)
  → tryReloadIfExpired(...)   ← TTL 만료 & tryLock 성공한 1개 스레드만 DB 재조회 후 set
  → snapshotRef.get().get...

[수동 리로드]
tryReload(dMdc, input)        ← 성공 시 교체, 실패 시 기존 유지 + false
reloadOrThrow(dMdc, input)    ← 성공 시 교체, 실패 시 예외
```

---

## 8. 주니어가 이 캐시를 수정할 때 절대 깨면 안 되는 규칙

1. **스냅샷 내부 Map을 외부에 mutable하게 노출하지 말 것.** 새 필드를 추가하더라도 반드시 `sanitize → copy → freeze` 3단 방어를 거쳐야 한다. (3장)
2. **부분 수정(`map.put`) 방식으로 바꾸지 말 것.** 이 캐시는 "전체 스냅샷 교체"가 전제다. 부분 수정하면 중간 상태 노출 + 동시성 문제가 터진다.
3. **`snapshotRef.set(next)`를 try 블록 중간으로 올리지 말 것.** 반드시 "정제까지 끝난 뒤 맨 끝"이어야 한다. 중간에 두면 실패 시 깨진 스냅샷이 노출된다.
4. **조회 메서드에 무거운 작업(DB/JSON 파싱/대규모 계산)을 넣지 말 것.** 락 없는 O(1) 읽기라는 핵심 장점이 사라진다.
5. **`tryReloadIfExpired`의 `tryLock()`을 `lock()`으로 바꾸지 말 것.** 모든 조회가 리로드 뒤에 줄 서게 되어 사실상 서비스 정지.
6. **`finally`의 `unlock()`을 제거/이동하지 말 것.** unlock이 한 번이라도 누락되면 리로드가 영구 차단되어 캐시가 영원히 stale로 굳는다.
7. **TTL(`TTL_MILLIS`) 정책을 바꿀 땐 "허용 stale 범위"를 함께 설명할 것.** 줄이면 DB 부하↑, 늘리면 stale window↑.

---

## 9. 알아둘 트레이드오프 (공짜가 아니다)

- **stale window 존재** — TTL(5분) 안에서는 옛 데이터가 보일 수 있다. "정확한 실시간성"보다 "서비스 연속성"을 택한 설계다.
- **리로드 비용 = 전체 재구성** — 변경분만 패치하지 않고 매번 전체 스냅샷을 새로 만든다.
- **교체 순간 메모리 2벌** — 이전 스냅샷과 새 스냅샷이 잠깐 동시에 존재한다(교체 후 옛 것은 GC 대상).

> 이 비용을 감수하는 이유: **읽기가 압도적으로 많고 갱신이 드문** 워크로드에서는, 이 단순·안전한 구조가 부분 수정 방식보다 훨씬 안정적이기 때문이다.

---

## 10. 향후 개선 후보 (물어보면 이 순서로)

1. **TTL 외부 설정화** — 현재 `TTL_MILLIS = 5분` 하드코딩. 환경설정으로 분리.
2. **버전별 캐시 키 전략** — 현재 `Input.versionOrNull`이 실제 로딩에 거의 안 쓰임. 버전별 메타가 필요하면 키 모델 재설계.
3. **테스트용 reset/replace 훅** — 정적 싱글톤이라 테스트 격리가 어려움.
4. **메트릭 연동** — reload 성공/실패 카운트, duration 히스토그램, 현재 스냅샷 size 게이지.

---

## 11. 근거로 확인한 파일

- `src/main/java/com/mydata/cache/apicache/ApiCommonMetaCache.java` — 캐시 엔진(TTL, 리로드 락, `sanitizeAndFreeze`, 스냅샷 교체, 실패 정책)
- `src/main/java/com/mydata/cache/ApiCommonMeta.java` — 최상위 스냅샷 객체
- `src/main/java/com/mydata/cache/ApiEndpointMetadataSnapshot.java` — 실제 조회 대상 3개 Map(+Set) 불변 스냅샷
- `src/main/java/com/mydata/cache/ApiEndpointRoutingSnapshot.java` — 확장 슬롯
- `src/main/java/com/mydata/cache/FreezeUtils.java` — 방어적 복사 + 불변화
- `src/main/java/com/mydata/cache/ApiCaches.java` — 싱글톤 진입점 + 부팅 초기화
- `src/main/java/com/mydata/cache/apicache/DefaultApiCommonMetaLoader.java` — DB row → 스냅샷 조립
- `src/main/java/com/mydata/cache/CacheReloadLogSupport.java` — 리로드 로그/diff

> 관련 문서: `docs/jvm-cache-handover.md`(캐시 전반 개요). 이 문서는 그중 **핵심 로직(스냅샷 모델·불변화·교체·동시성)을 코드 레벨로 심화**한 버전이다.
