package com.mydata.cache;

final class NoopCacheReloadLogPublisher implements CacheReloadLogPublisher {

    static final NoopCacheReloadLogPublisher INSTANCE = new NoopCacheReloadLogPublisher();

    private NoopCacheReloadLogPublisher() {
    }

    @Override
    public void publish(String topic, String message) {
        return;
    }
}
