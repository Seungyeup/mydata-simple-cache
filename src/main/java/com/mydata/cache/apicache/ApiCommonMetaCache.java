package com.mydata.cache.apicache; // 패키지 경로를 선언한다.

import com.mydata.cache.ApiCaches;
import com.mydata.cache.ApiCommonMeta;
import com.mydata.cache.CacheReloadLogPublisher;
import com.mydata.cache.CacheReloadLogSupport;
import com.mydata.cache.ApiEndpointMetadataSnapshot;
import com.mydata.cache.ApiEndpointRoutingSnapshot;
import com.mydata.cache.NoopCacheReloadLogPublisher;

import java.util.HashMap; // 로드 결과를 임시로 담아 스냅샷을 만들기 위해 가져온다.
import java.util.Map; // 스냅샷 내부의 Map 타입을 사용한다.
import java.util.concurrent.atomic.AtomicReference; // 락 없이 스냅샷 참조를 교체하기 위해 사용한다.
import java.util.concurrent.locks.ReentrantLock; // 리로드 동시 실행을 제어하기 위해 사용한다.

/**
 * 읽기(조회)가 매우 잦고 변경(리로드)이 드문 상황에 최적화된 "스냅샷" 캐시다.
 *
 * 핵심 아이디어:
 * - 읽기: AtomicReference로 가리키는 불변 ApiCommonMeta(내부 3개 Map)를 그대로 조회(락 없음, O(1))
 * - 변경: DB에서 전체를 다시 읽어 새 ApiCommonMeta를 만든 뒤, 참조를 한 번에 교체(스왑)
 */
public final class ApiCommonMetaCache { // 상속을 막아 동작을 고정하고 예측 가능하게 만든다.

    private static final long TTL_MILLIS = 5L * 60L * 1000L; // TTL을 5분으로 고정한다(스케줄러 없이 "조회 시" 갱신 트리거).

    private final ApiCommonMetaLoader loader; // DB 스냅샷을 가져오는 로더(무상태 구현; 호출 시 dbio/input 전달).
    private final AtomicReference<ApiCommonMeta> snapshotRef; // 현재 스냅샷(ApiCommonMeta)을 원자적으로 보관한다.
    private final ReentrantLock reloadLock; // 동시에 여러 스레드가 리로드하지 않도록 막는다.
    private final CacheReloadLogPublisher reloadLogPublisher;

    private volatile long lastSuccessfulReloadEpochMillis; // 마지막으로 리로드에 성공한 시각(밀리초)을 기록한다.

    public ApiCommonMetaCache(ApiCommonMetaLoader loader) { // 로더를 받아 캐시를 생성한다.
        this(loader, null);
    }

    public ApiCommonMetaCache(ApiCommonMetaLoader loader, CacheReloadLogPublisher reloadLogPublisher) {
        if (loader == null) {
            throw new IllegalArgumentException("loader must not be null");
        }
        this.loader = loader;
        this.snapshotRef = new AtomicReference<ApiCommonMeta>(ApiCommonMeta.empty());
        this.reloadLock = new ReentrantLock();
        this.lastSuccessfulReloadEpochMillis = 0L;
        this.reloadLogPublisher = (reloadLogPublisher == null ? NoopCacheReloadLogPublisher.INSTANCE : reloadLogPublisher);
    }

    public ApiCommonMeta snapshot() { // 현재 스냅샷 전체를 가져온다.
        // dbio를 필드로 보관하지 않으므로, 파라미터 없는 메서드는 자동 리로드를 트리거하지 않는다.
        return snapshotRef.get(); // 현재 스냅샷을 그대로 반환한다.
    }

    public ApiCommonMeta snapshot(ApiCaches.DMdcCacheMng dMdcCacheMng, ApiCaches.Input input) { // dbio/input을 받아 TTL 만료 시 자동 갱신을 시도한다.
        tryReloadIfExpired(dMdcCacheMng, input);
        return snapshotRef.get();
    }

    public String getApiUrlOrNull(String apiCode) { // API 코드로 URL을 조회한다.
        // 파라미터 없는 버전은 자동 리로드를 하지 않는다.
        return snapshotRef.get().getApiUrlOrNull(apiCode); // 스냅샷 내부 URL Map에서 조회한다.
    }

    public String getHttpMethodOrNull(String apiCode) { // API 코드로 HTTP 메서드를 조회한다.
        // 파라미터 없는 버전은 자동 리로드를 하지 않는다.
        return snapshotRef.get().getHttpMethodOrNull(apiCode); // 스냅샷 내부 메서드 Map에서 조회한다.
    }

    public String getApiUrlOrNull(ApiCaches.DMdcCacheMng dMdcCacheMng, ApiCaches.Input input, String apiCode) { // API 코드로 URL을 조회하며 TTL 자동 갱신을 지원한다.
        tryReloadIfExpired(dMdcCacheMng, input);
        return snapshotRef.get().getApiUrlOrNull(apiCode);
    }

    public String getHttpMethodOrNull(ApiCaches.DMdcCacheMng dMdcCacheMng, ApiCaches.Input input, String apiCode) { // API 코드로 HTTP 메서드를 조회하며 TTL 자동 갱신을 지원한다.
        tryReloadIfExpired(dMdcCacheMng, input);
        return snapshotRef.get().getHttpMethodOrNull(apiCode);
    }

    private void tryReloadIfExpired(ApiCaches.DMdcCacheMng dMdcCacheMng, ApiCaches.Input input) { // TTL 만료 시 "자동"으로 리로드를 트리거한다.

        long last = lastSuccessfulReloadEpochMillis; // 마지막 성공 리로드 시각을 읽는다.
        long now = System.currentTimeMillis(); // 현재 시각을 구한다.

        // 아직 한 번도 로드하지 않았거나, TTL이 지나지 않았다면 아무 것도 하지 않는다.
        if (last != 0L && (now - last) < TTL_MILLIS) { // TTL이 아직 유효하면
            return; // 리로드할 필요가 없다.
        }

        // 동시에 여러 스레드가 DB를 두드리지 않도록, 오직 1개의 스레드만 리로드를 실행하게 한다.
        if (!reloadLock.tryLock()) { // 락을 못 잡으면(다른 스레드가 리로드 중이면)
            return; // 이 스레드는 기존 스냅샷을 그대로 사용한다(대기하지 않음).
        }

        String logMessage = null;
        long startedAtEpochMillis = 0L;
        long startNanos = 0L;
        ApiCommonMeta nextForSize = null;
        int diffAdded = -1;
        int diffRemoved = -1;
        int diffChanged = -1;

        try { // 락을 잡았으니, 여기서만 실제 리로드를 시도한다.

            // 락 획득 전후로 다른 스레드가 이미 갱신했을 수 있으니 TTL을 한 번 더 확인한다.
            long lastAfterLock = lastSuccessfulReloadEpochMillis; // 락 획득 후의 마지막 성공 리로드 시각을 다시 읽는다.
            long nowAfterLock = System.currentTimeMillis(); // 락 획득 후 현재 시각을 다시 구한다.
            if (lastAfterLock != 0L && (nowAfterLock - lastAfterLock) < TTL_MILLIS) { // 이미 최신이면
                return; // 중복 리로드를 하지 않는다.
            }

            // 여기부터는 DB 접근이 발생한다(전체 스냅샷 1회 로드).
            startedAtEpochMillis = System.currentTimeMillis();
            startNanos = System.nanoTime();
            ApiCommonMeta prev = snapshotRef.get();
            ApiCommonMeta loaded = loader.loadSnapshot(dMdcCacheMng, input); // 로더로부터 전체 스냅샷을 읽는다.
            ApiCommonMeta next = sanitizeAndFreeze(loaded); // 널/중복 등을 정리하고 불변 스냅샷으로 만든다.
            nextForSize = next;
            snapshotRef.set(next); // 참조를 한 번에 교체하여 스냅샷을 갱신한다.
            lastSuccessfulReloadEpochMillis = nowAfterLock; // 성공 시각을 기록한다.

            int[] diff = diffApiCommonMeta(prev, next);
            diffAdded = diff[0];
            diffRemoved = diff[1];
            diffChanged = diff[2];

            long durationMillis = (System.nanoTime() - startNanos) / 1000000L;
            logMessage = CacheReloadLogSupport.toJson("ApiCommonMetaCache", "AUTO_TTL", true, startedAtEpochMillis, durationMillis, next.size(), diffAdded, diffRemoved, diffChanged, null);

        } catch (Exception e) {
            long durationMillis = (startNanos == 0L ? 0L : (System.nanoTime() - startNanos) / 1000000L);
            int size = (nextForSize == null ? snapshotRef.get().size() : nextForSize.size());
            logMessage = CacheReloadLogSupport.toJson("ApiCommonMetaCache", "AUTO_TTL", false, startedAtEpochMillis, durationMillis, size, -1, -1, -1, e);
        } finally { // 어떤 경우든
            reloadLock.unlock(); // 락을 반드시 해제한다.
            CacheReloadLogSupport.publishSafely(reloadLogPublisher, logMessage);
        }
    }

    public int size() { // 캐시 엔트리 수를 반환한다.
        return snapshotRef.get().size(); // 현재 스냅샷의 크기를 반환한다.
    }

    public long getLastSuccessfulReloadEpochMillis() { // 마지막 성공 리로드 시각을 반환한다.
        return lastSuccessfulReloadEpochMillis; // volatile 필드를 그대로 반환한다.
    }

    public void reloadOrThrow(ApiCaches.DMdcCacheMng dMdcCacheMng, ApiCaches.Input input) { // DB에서 스냅샷을 다시 읽어 캐시를 교체한다(실패 시 예외).
        reloadLock.lock(); // 리로드 중에는 다른 리로드를 막기 위해 락을 건다.
        String logMessage = null;
        long startedAtEpochMillis = 0L;
        long startNanos = 0L;
        try { // 락을 해제하기 위해 try/finally를 쓴다.
            startedAtEpochMillis = System.currentTimeMillis();
            startNanos = System.nanoTime();
            ApiCommonMeta prev = snapshotRef.get();
            ApiCommonMeta loaded = loader.loadSnapshot(dMdcCacheMng, input); // 호출 시점 파라미터로 전체 스냅샷을 읽는다.
            ApiCommonMeta next = sanitizeAndFreeze(loaded); // 널/중복 등을 정리하고 불변 스냅샷으로 만든다.
            snapshotRef.set(next); // 참조를 한 번에 교체하여 "스냅샷" 업데이트를 끝낸다.
            lastSuccessfulReloadEpochMillis = System.currentTimeMillis(); // 성공 시각을 기록한다.
            long durationMillis = (System.nanoTime() - startNanos) / 1000000L;
            int[] diff = diffApiCommonMeta(prev, next);
            logMessage = CacheReloadLogSupport.toJson("ApiCommonMetaCache", "MANUAL_RELOAD_OR_THROW", true, startedAtEpochMillis, durationMillis, next.size(), diff[0], diff[1], diff[2], null);
        } catch (RuntimeException e) { // 로더가 런타임 예외를 던지면
            long durationMillis = (startNanos == 0L ? 0L : (System.nanoTime() - startNanos) / 1000000L);
            logMessage = CacheReloadLogSupport.toJson("ApiCommonMetaCache", "MANUAL_RELOAD_OR_THROW", false, startedAtEpochMillis, durationMillis, snapshotRef.get().size(), -1, -1, -1, e);
            throw e; // 호출자에게 그대로 전달한다(스타트업 실패 등 강하게 처리하고 싶을 수 있다).
        } catch (Exception e) { // 체크 예외를 던지는 구현을 대비해 포괄적으로 잡는다.
            long durationMillis = (startNanos == 0L ? 0L : (System.nanoTime() - startNanos) / 1000000L);
            logMessage = CacheReloadLogSupport.toJson("ApiCommonMetaCache", "MANUAL_RELOAD_OR_THROW", false, startedAtEpochMillis, durationMillis, snapshotRef.get().size(), -1, -1, -1, e);
            throw new CacheReloadException("failed to reload api endpoint cache", e); // 캐시 리로드 실패로 감싸서 올린다.
        } finally { // 어떤 경우든
            reloadLock.unlock(); // 락을 반드시 해제한다.
            CacheReloadLogSupport.publishSafely(reloadLogPublisher, logMessage);
        }
    }

    public boolean tryReload(ApiCaches.DMdcCacheMng dMdcCacheMng, ApiCaches.Input input) { // 리로드를 시도하되, 실패하면 기존 스냅샷을 유지하고 false를 반환한다.
        reloadLock.lock(); // 동시에 리로드가 겹치지 않도록 락을 건다.
        String logMessage = null;
        long startedAtEpochMillis = 0L;
        long startNanos = 0L;
        try { // 성공/실패에 관계없이 락 해제를 보장한다.
            startedAtEpochMillis = System.currentTimeMillis();
            startNanos = System.nanoTime();
            ApiCommonMeta prev = snapshotRef.get();
            ApiCommonMeta loaded = loader.loadSnapshot(dMdcCacheMng, input); // DB에서 전체를 다시 읽는다.
            ApiCommonMeta next = sanitizeAndFreeze(loaded); // 불변 스냅샷으로 변환한다.
            snapshotRef.set(next); // 스냅샷을 교체한다.
            lastSuccessfulReloadEpochMillis = System.currentTimeMillis(); // 성공 시각을 기록한다.
            long durationMillis = (System.nanoTime() - startNanos) / 1000000L;
            int[] diff = diffApiCommonMeta(prev, next);
            logMessage = CacheReloadLogSupport.toJson("ApiCommonMetaCache", "MANUAL_TRY_RELOAD", true, startedAtEpochMillis, durationMillis, next.size(), diff[0], diff[1], diff[2], null);
            return true; // 성공했으므로 true를 반환한다.
        } catch (Exception e) { // 실패하면
            long durationMillis = (startNanos == 0L ? 0L : (System.nanoTime() - startNanos) / 1000000L);
            logMessage = CacheReloadLogSupport.toJson("ApiCommonMetaCache", "MANUAL_TRY_RELOAD", false, startedAtEpochMillis, durationMillis, snapshotRef.get().size(), -1, -1, -1, e);
            return false; // 기존 스냅샷을 유지한 채 실패만 알린다(DB 장애 시 서비스 연속성을 위해).
        } finally { // 어떤 경우든
            reloadLock.unlock(); // 락을 해제한다.
            CacheReloadLogSupport.publishSafely(reloadLogPublisher, logMessage);
        }
    }

    private static int[] diffApiCommonMeta(ApiCommonMeta prev, ApiCommonMeta next) {
        if (prev == null) {
            prev = ApiCommonMeta.empty();
        }
        if (next == null) {
            next = ApiCommonMeta.empty();
        }

        Map<String, String> prevKeys = prev.apiCodeByApiCodeView();
        Map<String, String> nextKeys = next.apiCodeByApiCodeView();

        int added = 0;
        int removed = 0;
        int changed = 0;

        for (String key : nextKeys.keySet()) {
            if (!prevKeys.containsKey(key)) {
                added++;
                continue;
            }
            String prevUrl = prev.getApiUrlOrNull(key);
            String nextUrl = next.getApiUrlOrNull(key);
            String prevMethod = prev.getHttpMethodOrNull(key);
            String nextMethod = next.getHttpMethodOrNull(key);
            if (!equalsNullable(prevUrl, nextUrl) || !equalsNullable(prevMethod, nextMethod)) {
                changed++;
            }
        }

        for (String key : prevKeys.keySet()) {
            if (!nextKeys.containsKey(key)) {
                removed++;
            }
        }

        return new int[] { added, removed, changed };
    }

    private static boolean equalsNullable(String a, String b) {
        if (a == null) {
            return b == null;
        }
        return a.equals(b);
    }

    private static ApiCommonMeta sanitizeAndFreeze(ApiCommonMeta loaded) { // 로더 결과를 캐시에 안전한 형태로 바꾼다.
        if (loaded == null) { // 로더가 null을 주면
            return ApiCommonMeta.empty(); // 빈 스냅샷으로 둔다(널은 쓰지 않는다).
        }

        ApiEndpointMetadataSnapshot endpointMetadata = sanitizeApiEndpointMetadataSnapshot(loaded.endpointMetadata()); // 클래스1(3개 Map)을 정리/불변화한다.
        ApiEndpointRoutingSnapshot endpointRouting = sanitizeApiEndpointRoutingSnapshot(loaded.endpointRouting()); // 클래스2(3개 Map)을 정리/불변화한다.

        if (endpointMetadata.size() == 0 && endpointRouting.size() == 0) { // 둘 다 비어 있으면
            return ApiCommonMeta.empty(); // 빈 스냅샷을 반환한다.
        }

        return new ApiCommonMeta(endpointMetadata, endpointRouting); // 클래스1/클래스2를 포함한 스냅샷을 반환한다.
    }

    private static ApiEndpointMetadataSnapshot sanitizeApiEndpointMetadataSnapshot(ApiEndpointMetadataSnapshot loaded) { // 클래스1의 3개 Map을 정리한다.
        if (loaded == null) { // null이면
            return ApiEndpointMetadataSnapshot.empty(); // 빈 클래스로 대체한다.
        }

        String version = loaded.getVersionOrNull(); // 문자열 필드는 그대로 보존한다(불변 String).
        Map<String, java.util.Set<String>> stringSetMap = sanitizeStringSetMap(loaded.stringSetByApiCodeView()); // Map<String,Set<String>> 형태의 추가 메타 Map도 정리한다.

        Map<String, String> apiCodeMap = new HashMap<String, String>(); // API 코드 -> API 코드 Map이다.
        Map<String, String> urlMap = new HashMap<String, String>(); // API 코드 -> URL Map이다.
        Map<String, String> methodMap = new HashMap<String, String>(); // API 코드 -> HTTP 메서드 Map이다.

        copyStringMap(loaded.apiCodeByApiCodeView(), apiCodeMap, null); // 키만 확보한다.
        copyStringMap(loaded.apiUrlByApiCodeView(), urlMap, apiCodeMap); // URL을 복사하며 키 합집합을 만든다.
        copyStringMap(loaded.httpMethodByApiCodeView(), methodMap, apiCodeMap); // 메서드를 복사하며 키 합집합을 만든다.

        for (String key : apiCodeMap.keySet()) { // 모든 키에 대해
            apiCodeMap.put(key, key); // 값도 키로 통일한다.
        }

        if (apiCodeMap.isEmpty() && urlMap.isEmpty() && methodMap.isEmpty() && (stringSetMap == null || stringSetMap.isEmpty())) { // 전부 비어 있으면
            return ApiEndpointMetadataSnapshot.empty(); // 빈 클래스로 둔다.
        }

        return new ApiEndpointMetadataSnapshot(version, apiCodeMap, urlMap, methodMap, stringSetMap); // 정리된 클래스를 반환한다.
    }

    private static Map<String, java.util.Set<String>> sanitizeStringSetMap(Map<String, java.util.Set<String>> source) { // Map<String,Set<String>>을 정리한다.
        if (source == null || source.isEmpty()) { // null/빈이면
            return java.util.Collections.<String, java.util.Set<String>>emptyMap(); // 빈 Map을 반환한다.
        }

        Map<String, java.util.Set<String>> copy = new HashMap<String, java.util.Set<String>>(source.size() * 2); // 결과 Map을 만든다.
        for (Map.Entry<String, java.util.Set<String>> entry : source.entrySet()) { // 모든 엔트리를 순회한다.
            if (entry == null) { // 엔트리 자체가 null이면
                continue; // 무시한다.
            }
            String key = entry.getKey();
            if (key == null || key.isEmpty()) {
                continue; // 제외한다.
            }

            java.util.Set<String> raw = entry.getValue(); // 원본 Set을 읽는다.
            if (raw == null || raw.isEmpty()) { // null/빈이면
                continue; // 제외한다.
            }

            java.util.Set<String> setCopy = new java.util.HashSet<String>(raw.size() * 2); // Set을 방어적 복사한다.
            for (String v : raw) { // 값을 순회한다.
                if (v == null || v.isEmpty()) {
                    continue;
                }
                setCopy.add(v);
            }

            if (setCopy.isEmpty()) { // 정리 후 비면
                continue; // 제외한다.
            }

            copy.put(key, java.util.Collections.unmodifiableSet(setCopy)); // 불변 Set으로 감싸 넣는다.
        }

        if (copy.isEmpty()) { // 정리 후 비면
            return java.util.Collections.<String, java.util.Set<String>>emptyMap(); // 빈 Map을 반환한다.
        }

        return java.util.Collections.unmodifiableMap(copy); // 불변 Map으로 감싸 반환한다.
    }

    private static ApiEndpointRoutingSnapshot sanitizeApiEndpointRoutingSnapshot(ApiEndpointRoutingSnapshot loaded) { // 클래스2의 3개 Map을 정리한다.
        if (loaded == null) { // null이면
            return ApiEndpointRoutingSnapshot.empty(); // 빈 클래스로 대체한다.
        }

        Map<String, String> apiCodeMap = new HashMap<String, String>(); // API 코드 -> API 코드 Map이다.
        Map<String, String> urlMap = new HashMap<String, String>(); // API 코드 -> URL Map이다.
        Map<String, String> methodMap = new HashMap<String, String>(); // API 코드 -> HTTP 메서드 Map이다.

        copyStringMap(loaded.apiCodeByApiCodeView(), apiCodeMap, null); // 키만 확보한다.
        copyStringMap(loaded.apiUrlByApiCodeView(), urlMap, apiCodeMap); // URL을 복사하며 키 합집합을 만든다.
        copyStringMap(loaded.httpMethodByApiCodeView(), methodMap, apiCodeMap); // 메서드를 복사하며 키 합집합을 만든다.

        for (String key : apiCodeMap.keySet()) { // 모든 키에 대해
            apiCodeMap.put(key, key); // 값도 키로 통일한다.
        }

        if (apiCodeMap.isEmpty() && urlMap.isEmpty() && methodMap.isEmpty()) { // 전부 비어 있으면
            return ApiEndpointRoutingSnapshot.empty(); // 빈 클래스로 둔다.
        }

        return new ApiEndpointRoutingSnapshot(apiCodeMap, urlMap, methodMap); // 정리된 클래스를 반환한다.
    }

    private static void copyStringMap(Map<String, String> source, Map<String, String> target, Map<String, String> keyCollector) { // Map<String,String>을 정리하며 복사한다.
        if (source == null || source.isEmpty()) { // null/빈이면
            return; // 아무 것도 하지 않는다.
        }
        for (Map.Entry<String, String> entry : source.entrySet()) { // 모든 엔트리를 순회한다.
            if (entry == null) { // 엔트리 자체가 null이면
                continue; // 방어적으로 무시한다.
            }
            String key = entry.getKey();
            if (key == null || key.isEmpty()) {
                continue;
            }
            String value = entry.getValue(); // 값을 읽는다.
            if (value == null || value.isEmpty()) {
                continue;
            }
            target.put(key, value);
            if (keyCollector != null) { // 키 수집 Map이 있으면
                keyCollector.put(key, key); // 키를 합집합에 포함시킨다.
            }
        }
    }

    /**
     * 리로드 실패를 의미하는 런타임 예외다(호출자가 선택적으로 잡아 처리할 수 있다).
     */
    public static final class CacheReloadException extends RuntimeException { // 체크 예외를 강제하지 않기 위해 런타임 예외로 둔다.
        public CacheReloadException(String message, Throwable cause) { // 메시지와 원인을 받아
            super(message, cause); // 부모(RuntimeException)에 전달한다.
        }
    }
}
