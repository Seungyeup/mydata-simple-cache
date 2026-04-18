package com.mydata.cache.examples;

import com.mydata.cache.ApiCaches;
import com.mydata.cache.apicache.ApiCommonMetaCache;
import java.util.Arrays;
import java.util.List;

public final class ApiEndpointCacheUsageExample {

    private ApiEndpointCacheUsageExample() {
    }

    public static void main(String[] args) {
        ApiCaches.DMdcCacheMng dMdcCacheMng = new FakeDMdcCacheMng();
        ApiCaches.Input input = new ApiCaches.Input("BA01");

        ApiCommonMetaCache cache = ApiCaches.apiEndpointCache(dMdcCacheMng, input);

        System.out.println("cacheSize=" + cache.size());
        System.out.println("apiUrl=" + cache.getApiUrlOrNull("BA01"));
        System.out.println("httpMethod=" + cache.getHttpMethodOrNull("BA01"));
    }

    private static final class FakeDMdcCacheMng implements ApiCaches.DMdcCacheMng {
        @Override
        public List<ApiCaches.DbioDto> selectApiMetaInfoList00(String apiCode) {
            return Arrays.asList(
                    new ApiCaches.DbioDto("BA01", "/v1/balance", "POST"),
                    new ApiCaches.DbioDto("BA02", "/v1/transfer", "GET")
            );
        }
    }
}
