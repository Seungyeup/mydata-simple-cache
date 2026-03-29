package com.mydata.cache;

public interface ApiValidationMetaLoader {

    ApiValidationMeta loadSnapshot(ApiCaches.DValidationCacheMng dValidationCacheMng, ApiCaches.Input input);
}
