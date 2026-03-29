package com.mydata.cache;

public interface CacheReloadLogPublisher {

    void publish(String topic, String message) throws Exception;
}
