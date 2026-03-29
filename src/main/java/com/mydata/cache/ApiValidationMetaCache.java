package com.mydata.cache;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * API별 JSON input/output 필수 필드를 검증하기 위한 validation 스냅샷 캐시다.
 *
 * - DB에서 필수 필드 메타를 "전체 스냅샷"으로 읽어온다.
 * - 조회/검증 시 TTL 만료면 요청 스레드가 1회 리로드를 시도한다.
 */
public final class ApiValidationMetaCache {

    private static final long TTL_MILLIS = 5L * 60L * 1000L;

    private final ApiValidationMetaLoader loader;
    private final AtomicReference<ApiValidationMeta> snapshotRef;
    private final ReentrantLock reloadLock;
    private final CacheReloadLogPublisher reloadLogPublisher;

    private volatile long lastSuccessfulReloadEpochMillis;
    private volatile String lastReloadFailureMessage;

    public ApiValidationMetaCache(ApiValidationMetaLoader loader) {
        this(loader, null);
    }

    public ApiValidationMetaCache(ApiValidationMetaLoader loader, CacheReloadLogPublisher reloadLogPublisher) {
        if (loader == null) {
            throw new IllegalArgumentException("loader must not be null");
        }
        this.loader = loader;
        this.snapshotRef = new AtomicReference<ApiValidationMeta>(ApiValidationMeta.empty());
        this.reloadLock = new ReentrantLock();
        this.lastSuccessfulReloadEpochMillis = 0L;
        this.lastReloadFailureMessage = null;
        this.reloadLogPublisher = (reloadLogPublisher == null ? NoopCacheReloadLogPublisher.INSTANCE : reloadLogPublisher);
    }

    public ApiValidationMeta snapshot() {
        // 파라미터 없는 버전은 자동 리로드를 트리거하지 않는다.
        return snapshotRef.get();
    }

    public ApiValidationMeta snapshot(ApiCaches.DValidationCacheMng dValidationCacheMng, ApiCaches.Input input) {
        tryReloadIfExpired(dValidationCacheMng, input);
        return snapshotRef.get();
    }

    public int size() {
        return snapshotRef.get().size();
    }

    public long getLastSuccessfulReloadEpochMillis() {
        return lastSuccessfulReloadEpochMillis;
    }

    public String getLastReloadFailureMessageOrNull() {
        return lastReloadFailureMessage;
    }


    private void tryReloadIfExpired(ApiCaches.DValidationCacheMng dValidationCacheMng, ApiCaches.Input input) {
        long last = lastSuccessfulReloadEpochMillis;
        long now = System.currentTimeMillis();

        if (last != 0L && (now - last) < TTL_MILLIS) {
            return;
        }

        if (!reloadLock.tryLock()) {
            return;
        }

        String logMessage = null;
        long startedAtEpochMillis = 0L;
        long startNanos = 0L;
        ApiValidationMeta nextForSize = null;
        int diffAdded = -1;
        int diffRemoved = -1;
        int diffChanged = -1;

        try {
            long lastAfterLock = lastSuccessfulReloadEpochMillis;
            long nowAfterLock = System.currentTimeMillis();
            if (lastAfterLock != 0L && (nowAfterLock - lastAfterLock) < TTL_MILLIS) {
                return;
            }

            startedAtEpochMillis = System.currentTimeMillis();
            startNanos = System.nanoTime();
            if (dValidationCacheMng == null || input == null) {
                return;
            }
            ApiValidationMeta prev = snapshotRef.get();
            ApiValidationMeta loaded = loader.loadSnapshot(dValidationCacheMng, input);
            ApiValidationMeta next = (loaded == null ? ApiValidationMeta.empty() : loaded);
            nextForSize = next;
            snapshotRef.set(next);
            lastSuccessfulReloadEpochMillis = nowAfterLock;
            lastReloadFailureMessage = null;

            long durationMillis = (System.nanoTime() - startNanos) / 1000000L;
            int[] diff = diffApiValidationMeta(prev, next);
            diffAdded = diff[0];
            diffRemoved = diff[1];
            diffChanged = diff[2];
            logMessage = CacheReloadLogSupport.toJson("ApiValidationMetaCache", "AUTO_TTL", true, startedAtEpochMillis, durationMillis, next.size(), diffAdded, diffRemoved, diffChanged, null);

        } catch (Exception e) {
            lastReloadFailureMessage = "reload failed: " + e.getClass().getName() + ": " + e.getMessage();
            long durationMillis = (startNanos == 0L ? 0L : (System.nanoTime() - startNanos) / 1000000L);
            int size = (nextForSize == null ? snapshotRef.get().size() : nextForSize.size());
            logMessage = CacheReloadLogSupport.toJson("ApiValidationMetaCache", "AUTO_TTL", false, startedAtEpochMillis, durationMillis, size, -1, -1, -1, e);
        } finally {
            reloadLock.unlock();
            CacheReloadLogSupport.publishSafely(reloadLogPublisher, logMessage);
        }
    }

    public void reloadOrThrow(ApiCaches.DValidationCacheMng dValidationCacheMng, ApiCaches.Input input) {
        reloadLock.lock();
        String logMessage = null;
        long startedAtEpochMillis = 0L;
        long startNanos = 0L;
        try {
            startedAtEpochMillis = System.currentTimeMillis();
            startNanos = System.nanoTime();
            ApiValidationMeta prev = snapshotRef.get();
            ApiValidationMeta loaded = loader.loadSnapshot(dValidationCacheMng, input);
            ApiValidationMeta next = (loaded == null ? ApiValidationMeta.empty() : loaded);
            snapshotRef.set(next);
            lastSuccessfulReloadEpochMillis = System.currentTimeMillis();
            lastReloadFailureMessage = null;
            long durationMillis = (System.nanoTime() - startNanos) / 1000000L;
            int[] diff = diffApiValidationMeta(prev, next);
            logMessage = CacheReloadLogSupport.toJson("ApiValidationMetaCache", "MANUAL_RELOAD_OR_THROW", true, startedAtEpochMillis, durationMillis, next.size(), diff[0], diff[1], diff[2], null);
        } catch (RuntimeException e) {
            long durationMillis = (startNanos == 0L ? 0L : (System.nanoTime() - startNanos) / 1000000L);
            logMessage = CacheReloadLogSupport.toJson("ApiValidationMetaCache", "MANUAL_RELOAD_OR_THROW", false, startedAtEpochMillis, durationMillis, snapshotRef.get().size(), -1, -1, -1, e);
            throw e;
        } catch (Exception e) {
            long durationMillis = (startNanos == 0L ? 0L : (System.nanoTime() - startNanos) / 1000000L);
            logMessage = CacheReloadLogSupport.toJson("ApiValidationMetaCache", "MANUAL_RELOAD_OR_THROW", false, startedAtEpochMillis, durationMillis, snapshotRef.get().size(), -1, -1, -1, e);
            throw new CacheReloadException("failed to reload api validation cache", e);
        } finally {
            reloadLock.unlock();
            CacheReloadLogSupport.publishSafely(reloadLogPublisher, logMessage);
        }
    }

    public boolean tryReload(ApiCaches.DValidationCacheMng dValidationCacheMng, ApiCaches.Input input) {
        reloadLock.lock();
        String logMessage = null;
        long startedAtEpochMillis = 0L;
        long startNanos = 0L;
        try {
            startedAtEpochMillis = System.currentTimeMillis();
            startNanos = System.nanoTime();
            ApiValidationMeta prev = snapshotRef.get();
            ApiValidationMeta loaded = loader.loadSnapshot(dValidationCacheMng, input);
            ApiValidationMeta next = (loaded == null ? ApiValidationMeta.empty() : loaded);
            snapshotRef.set(next);
            lastSuccessfulReloadEpochMillis = System.currentTimeMillis();
            lastReloadFailureMessage = null;
            long durationMillis = (System.nanoTime() - startNanos) / 1000000L;
            int[] diff = diffApiValidationMeta(prev, next);
            logMessage = CacheReloadLogSupport.toJson("ApiValidationMetaCache", "MANUAL_TRY_RELOAD", true, startedAtEpochMillis, durationMillis, next.size(), diff[0], diff[1], diff[2], null);
            return true;
        } catch (Exception e) {
            lastReloadFailureMessage = "reload failed: " + e.getClass().getName() + ": " + e.getMessage();
            long durationMillis = (startNanos == 0L ? 0L : (System.nanoTime() - startNanos) / 1000000L);
            logMessage = CacheReloadLogSupport.toJson("ApiValidationMetaCache", "MANUAL_TRY_RELOAD", false, startedAtEpochMillis, durationMillis, snapshotRef.get().size(), -1, -1, -1, e);
            return false;
        } finally {
            reloadLock.unlock();
            CacheReloadLogSupport.publishSafely(reloadLogPublisher, logMessage);
        }
    }

    private static int[] diffApiValidationMeta(ApiValidationMeta prev, ApiValidationMeta next) {
        if (prev == null) {
            prev = ApiValidationMeta.empty();
        }
        if (next == null) {
            next = ApiValidationMeta.empty();
        }

        Set<ApiId> prevCodes = prev.apiIdsView();
        Set<ApiId> nextCodes = next.apiIdsView();

        int added = 0;
        int removed = 0;
        int changed = 0;

        for (ApiId code : nextCodes) {
            if (!prevCodes.contains(code)) {
                added++;
                continue;
            }
            if (!prev.getRequiredInputJsonPointersOrEmpty(code).equals(next.getRequiredInputJsonPointersOrEmpty(code))) {
                changed++;
                continue;
            }
            if (!prev.getRequiredOutputJsonPointersOrEmpty(code).equals(next.getRequiredOutputJsonPointersOrEmpty(code))) {
                changed++;
            }
        }

        for (ApiId code : prevCodes) {
            if (!nextCodes.contains(code)) {
                removed++;
            }
        }

        return new int[] { added, removed, changed };
    }

    public static final class CacheReloadException extends RuntimeException {
        public CacheReloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

}
