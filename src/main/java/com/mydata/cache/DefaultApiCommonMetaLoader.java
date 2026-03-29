package com.mydata.cache;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ApiCommonMetaLoader의 기본(top-level) 구현. 상태를 보관하지 않는다.
 */
public final class DefaultApiCommonMetaLoader implements ApiCommonMetaLoader {

    @Override
    public ApiCommonMeta loadSnapshot(ApiCaches.DMdcCacheMng dMdcCacheMng, ApiCaches.Input input) {
        if (dMdcCacheMng == null) {
            throw new IllegalArgumentException("dMdcCacheMng must not be null");
        }
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }

        List<ApiCaches.DbioDto> rows = dMdcCacheMng.selectApiMetaInfoList00(input.getApiCode());
        if (rows == null || rows.isEmpty()) {
            return ApiCommonMeta.empty();
        }

        Map<String, String> apiCodeMap = new HashMap<String, String>();
        Map<String, String> urlMap = new HashMap<String, String>();
        Map<String, String> httpMethodMap = new HashMap<String, String>();
        Map<String, java.util.Set<String>> stringSetMap = new HashMap<String, java.util.Set<String>>();

        for (ApiCaches.DbioDto row : rows) {
            if (row == null) {
                continue;
            }
            String apiKey = row.getApiCode();
            String url = row.getApiUrl();
            String httpMethod = row.getHttpMethod();

            apiCodeMap.put(apiKey, apiKey);
            urlMap.put(apiKey, url);
            httpMethodMap.put(apiKey, httpMethod);

            java.util.Set<String> set = new java.util.HashSet<String>();
            set.add("TAG_A");
            set.add("TAG_B");
            stringSetMap.put(apiKey, set);
        }

        Class1 class1 = new Class1("v1", apiCodeMap, urlMap, httpMethodMap, stringSetMap);
        Class2 class2 = Class2.empty();
        return new ApiCommonMeta(class1, class2);
    }
}

