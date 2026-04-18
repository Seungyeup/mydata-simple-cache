package com.mydata.cache.validation;

import com.mydata.cache.ApiCaches;

public interface ApiValidationMetaLoader {

    ApiValidationMeta loadSnapshot(ApiCaches.DValidationCacheMng dValidationCacheMng, ApiCaches.Input input);
}
