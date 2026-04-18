package com.mydata.cache;

public final class NoopCacheReloadLogPublisher implements CacheReloadLogPublisher {

    public static final NoopCacheReloadLogPublisher INSTANCE = new NoopCacheReloadLogPublisher();

    private NoopCacheReloadLogPublisher() {
    }

    @Override
    public void publish(String topic, String message) {
        return;
    }
}
