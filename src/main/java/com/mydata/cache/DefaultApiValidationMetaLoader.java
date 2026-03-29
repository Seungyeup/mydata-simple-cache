package com.mydata.cache; // 패키지 경로를 선언한다.

import java.util.HashMap; // 결과 Map을 만들기 위해 사용한다.
import java.util.HashSet; // 값(Set) 누적을 위해 사용한다.
import java.util.Map; // Map 타입을 사용한다.
import java.util.Set; // Set 타입을 사용한다.

/**
 * ApiValidationMetaLoader의 기본(top-level) 구현이다.
 */
public final class DefaultApiValidationMetaLoader implements ApiValidationMetaLoader { // 구현 클래스는 상속 없이 동작을 고정한다.

    @Override
    public ApiValidationMeta loadSnapshot(ApiCaches.DValidationCacheMng dValidationCacheMng, ApiCaches.Input input) { // DBIO에서 메타를 읽어 스냅샷을 만든다.
        // 예시 input(rows):
        // - (version=v1, apiCode=BA01, direction=IN, depth=0, leading=N,     item=item4)
        // - (version=v1, apiCode=BA01, direction=IN, depth=1, leading=item4, item=item7)
        // - (version=v1, apiCode=BA01, direction=IN, depth=2, leading=item7, item=item8)
        // - (version=v1, apiCode=BA02, direction=IN, depth=0, leading=N,     item=item41)
        // - (version=v1, apiCode=BA02, direction=IN, depth=1, leading=item41,item=item71)
        // - (version=v1, apiCode=BA02, direction=IN, depth=2, leading=item71,item=item81)
        //
        // 예시 output(requiredInput):
        // - key=v1/BA01 -> {"/item4", "/item4/*/item7", "/item4/*/item7/*/item8"}
        // - key=v1/BA02 -> {"/item41", "/item41/*/item71", "/item41/*/item71/*/item81"}
        // 예시 input:
        // - input.getApiCode() == null (전체 스냅샷)
        //   1) (apiCode="BA01", direction="IN",  itemOrNull="/item1")
        //   2) (apiCode="BA01", direction="IN",  itemOrNull="/item4/*/item7")
        //   3) (apiCode="BA01", direction="OUT", itemOrNull="/resultCode")
        //
        // 예시 output:
        // - requiredInputJsonPointersByApiCode:
        //   { "BA01" -> {"/item1", "/item4/*/item7"} }
        // - requiredOutputJsonPointersByApiCode:
        //   { "BA01" -> {"/resultCode"} }

        if (dValidationCacheMng == null) { // DBIO 의존성이 없으면
            throw new IllegalArgumentException("dValidationCacheMng must not be null"); // 호출 계약 위반이므로 즉시 실패시킨다.
        }
        if (input == null) { // 조회 조건(input)이 없으면
            throw new IllegalArgumentException("input must not be null"); // 호출 계약 위반이므로 즉시 실패시킨다.
        }

        java.util.List<ApiCaches.DValidationDbioDto> rows = dValidationCacheMng.selectApiValidationFieldList00(input.getVersionOrNull(), input.getApiCode()); // DB에서 validation 메타 row 목록을 조회한다.
        if (rows == null || rows.isEmpty()) { // 결과가 없으면
            return ApiValidationMeta.empty(); // 빈 스냅샷을 반환한다.
        }

        Map<ApiId, Set<String>> requiredInput = new HashMap<ApiId, Set<String>>();
        Map<ApiId, Set<String>> requiredOutput = new HashMap<ApiId, Set<String>>();

        Map<Integer, java.util.List<ApiCaches.DValidationDbioDto>> inRowsByDepth = new HashMap<Integer, java.util.List<ApiCaches.DValidationDbioDto>>();
        Map<Integer, java.util.List<ApiCaches.DValidationDbioDto>> outRowsByDepth = new HashMap<Integer, java.util.List<ApiCaches.DValidationDbioDto>>();
        int maxInDepth = 0;
        int maxOutDepth = 0;

        for (ApiCaches.DValidationDbioDto row : rows) {
            if (row == null) {
                continue;
            }
            String apiCode = row.getApiCode();
            if (apiCode == null || apiCode.isEmpty()) {
                continue;
            }
            String direction = row.getDirection();
            if (!"IN".equals(direction) && !"OUT".equals(direction)) {
                continue;
            }

            int d = row.getDepth();
            if (d < 0) {
                d = 0;
            }

            if ("IN".equals(direction)) {
                java.util.List<ApiCaches.DValidationDbioDto> list = inRowsByDepth.get(d);
                if (list == null) {
                    list = new java.util.ArrayList<ApiCaches.DValidationDbioDto>();
                    inRowsByDepth.put(d, list);
                }
                list.add(row);
                if (d > maxInDepth) {
                    maxInDepth = d;
                }
            } else {
                java.util.List<ApiCaches.DValidationDbioDto> list = outRowsByDepth.get(d);
                if (list == null) {
                    list = new java.util.ArrayList<ApiCaches.DValidationDbioDto>();
                    outRowsByDepth.put(d, list);
                }
                list.add(row);
                if (d > maxOutDepth) {
                    maxOutDepth = d;
                }
            }
        }

        Map<ApiId, Map<Integer, Map<String, String>>> inPointerIndex = new HashMap<ApiId, Map<Integer, Map<String, String>>>();
        Map<ApiId, Map<Integer, Map<String, String>>> outPointerIndex = new HashMap<ApiId, Map<Integer, Map<String, String>>>();

        for (int d = 0; d <= maxInDepth; d++) {
            java.util.List<ApiCaches.DValidationDbioDto> list = inRowsByDepth.get(d);
            if (list == null || list.isEmpty()) {
                continue;
            }
            for (ApiCaches.DValidationDbioDto row : list) {
                ApiId apiId = ApiId.ofOrDefault(row.getVersionOrNull(), row.getApiCode());
                Map<Integer, Map<String, String>> depthIndex = inPointerIndex.get(apiId);
                if (depthIndex == null) {
                    depthIndex = new HashMap<Integer, Map<String, String>>();
                    inPointerIndex.put(apiId, depthIndex);
                }

                String pointer = buildJsonPointerOrNull(d, row.getLeadingOrNull(), row.getItemOrNull(), depthIndex);
                if (pointer == null) {
                    continue;
                }
                add(requiredInput, apiId, pointer);
                indexPointer(depthIndex, d, row.getItemOrNull(), pointer);
            }
        }

        for (int d = 0; d <= maxOutDepth; d++) {
            java.util.List<ApiCaches.DValidationDbioDto> list = outRowsByDepth.get(d);
            if (list == null || list.isEmpty()) {
                continue;
            }
            for (ApiCaches.DValidationDbioDto row : list) {
                ApiId apiId = ApiId.ofOrDefault(row.getVersionOrNull(), row.getApiCode());
                Map<Integer, Map<String, String>> depthIndex = outPointerIndex.get(apiId);
                if (depthIndex == null) {
                    depthIndex = new HashMap<Integer, Map<String, String>>();
                    outPointerIndex.put(apiId, depthIndex);
                }

                String pointer = buildJsonPointerOrNull(d, row.getLeadingOrNull(), row.getItemOrNull(), depthIndex);
                if (pointer == null) {
                    continue;
                }
                add(requiredOutput, apiId, pointer);
                indexPointer(depthIndex, d, row.getItemOrNull(), pointer);
            }
        }

        if (requiredInput.isEmpty() && requiredOutput.isEmpty()) { // 유효한 row가 하나도 없으면
            return ApiValidationMeta.empty(); // 빈 스냅샷을 반환한다.
        }

        return new ApiValidationMeta(requiredInput, requiredOutput); // 누적한 Map들로 불변 스냅샷을 만든다.
    }

    private void add(Map<ApiId, Set<String>> map, ApiId apiId, String pointer) {
        // 예시 input:
        // - apiId = v1/BA01
        // - pointer = "/item4/*/item7"
        //
        // 예시 output:
        // - map[v1/BA01]에 "/item4/*/item7"가 포함된다.
        // 예시 input:
        // - map = {}
        // - apiCode = "BA01"
        // - pointer = "/item1"
        //
        // 예시 output:
        // - map = { "BA01" -> {"/item1"} }
        //
        // (한 번 더 호출)
        // - add(map, "BA01", "/item2")
        // - map = { "BA01" -> {"/item1", "/item2"} }

        Set<String> set = map.get(apiId); // 기존 Set이 있는지 조회한다.
        if (set == null) { // 아직 apiCode에 대한 Set이 없으면
            set = new HashSet<String>(); // 새 Set을 만들고
            map.put(apiId, set); // Map에 등록한다.
        }
        set.add(pointer); // pointer를 Set에 추가한다(중복은 Set이 자동 제거).
    }

    private static String buildJsonPointerOrNull(int depth, String leadingOrNull, String itemOrNull, Map<Integer, Map<String, String>> depthIndex) { // depth/leading/item으로 JSON Pointer를 만든다.
        // 예시(단일 API에 대한 DB rows):
        // - (depth=0, leading=N,     item=item4)
        // - (depth=1, leading=item4, item=item7)
        // - (depth=2, leading=item7, item=item8)
        //
        // 이때 만들어지는 포인터(leading에 '/'가 없고 depth>=2에서 "직전 depth의 item"을 참조하는 규칙):
        // - depth=0: /item4
        // - depth=1: /item4/*/item7
        // - depth=2: /item4/*/item7/*/item8
        //
        // 예시(depthIndex 입력값; 같은 apiId에 대해 depth 증가 순서로 누적됨):
        // - depth=0 호출 직전: depthIndex = {}
        // - depth=0 처리+인덱싱 후: depthIndex = { 0: {"item4":"/item4"} }
        // - depth=1 호출 직전: depthIndex = { 0: {"item4":"/item4"} }
        // - depth=1 처리+인덱싱 후: depthIndex = { 0:{"item4":"/item4"}, 1:{"item7":"/item4/*/item7"} }
        // - depth=2 호출 직전: depthIndex = { 0:{"item4":"/item4"}, 1:{"item7":"/item4/*/item7"} }
        //
        // 예시 input/output(개별 호출 단위):
        // - (depth=0, leading=N, item="item1") -> "/item1"
        // - (depth=1, leading="item4", item="item7") -> "/item4/*/item7"
        // - (depth=2, leading="item7", item="item8") -> "/item4/*/item7/*/item8"  (depth=1에서 item7 포인터가 먼저 인덱싱되어 있어야 함)
        // - (depth=0, leading=N, item="/already/pointer") -> "/already/pointer" (item이 이미 포인터 문자열이면 그대로 사용)

        if (itemOrNull == null || itemOrNull.isEmpty() || "/".equals(itemOrNull)) { // item이 없거나 의미 없는 값이면
            return null; // 포인터를 만들 수 없다.
        }

        if (itemOrNull.charAt(0) == '/') { // item이 이미 "/..." 형태면
            return itemOrNull; // 그대로 JSON Pointer로 사용한다.
        }
        if (itemOrNull.indexOf('/') >= 0) { // item이 토큰이 아니라 경로처럼 보이면
            return null; // 현재 규칙에서는 지원하지 않는다(포인터면 맨 앞 '/'로 구분).
        }

        int d = depth; // DB의 depth 값을 지역 변수로 옮긴다.
        if (d < 0) { // 음수 depth는
            d = 0; // 0으로 보정한다.
        }
        if (d == 0) { // depth=0이면
            return "/" + itemOrNull; // 루트 바로 아래 토큰 1개 포인터다.
        }

        if (leadingOrNull == null || leadingOrNull.isEmpty()) { // depth>0인데 leading이 없으면
            return null; // 만들 수 없다.
        }
        if ("N".equals(leadingOrNull) || "NONE".equals(leadingOrNull) || "NULL".equals(leadingOrNull)) { // depth>0에서 leading이 N 계열이면
            return null; // 만들 수 없다.
        }

        if (d == 1) { // depth=1이면
            return "/" + leadingOrNull + "/*/" + itemOrNull; // leading 1토큰 + 배열 1회 + item 1토큰으로 만든다.
        }

        if (depthIndex == null) { // 직전 depth 포인터를 찾기 위한 인덱스가 없으면
            return null; // 만들 수 없다.
        }
        Map<String, String> prevDepth = depthIndex.get(d - 1); // (d-1) depth에서 item 토큰 -> 포인터 매핑을 찾는다.
        if (prevDepth == null || prevDepth.isEmpty()) { // 이전 depth 엔트리가 없으면
            return null; // 만들 수 없다.
        }

        String parentPointer = prevDepth.get(leadingOrNull); // leading을 "이전 depth의 item 토큰"으로 보고 부모 포인터를 찾는다.
        if (parentPointer == null || parentPointer.isEmpty()) { // 못 찾으면
            return null; // 만들 수 없다.
        }

        return parentPointer + "/*/" + itemOrNull; // 부모 포인터 뒤에 배열 1회 + item 토큰을 붙여 확장한다.
    }

    private static void indexPointer(Map<Integer, Map<String, String>> depthIndex, int depth, String itemOrNull, String pointer) {
        // depth>=2에서 leading이 "/" 없는 단일 토큰인 경우, leading을 "직전 depth의 item"으로 해석한다.
        // 이때 (직전 depth, item 토큰) -> (직전 depth에서 만든 포인터) 를 빠르게 찾기 위해 인덱스를 유지한다.
        //
        // 예:
        // - depth=1 row: leading=item4, item=item7 -> pointer=/item4/*/item7
        //   => depthIndex[1]["item7"] = "/item4/*/item7"
        // - depth=2 row: leading=item7, item=item8
        //   => parentPointer = depthIndex[1]["item7"]
        //   => pointer = parentPointer + "/*/item8" = "/item4/*/item7/*/item8"
        if (depthIndex == null) { // 인덱스가 없으면
            return; // 인덱싱을 하지 않는다.
        }
        if (pointer == null || pointer.isEmpty()) { // 포인터가 없으면
            return; // 인덱싱할 수 없다.
        }
        if (itemOrNull == null || itemOrNull.isEmpty()) { // item 토큰이 없으면
            return; // 키를 만들 수 없다.
        }
        if (itemOrNull.charAt(0) == '/' || itemOrNull.indexOf('/') >= 0) { // item이 단일 토큰이 아니면
            return; // 단일 토큰만 인덱싱한다.
        }

        Map<String, String> byToken = depthIndex.get(depth); // depth별 token->pointer Map.
        if (byToken == null) { // 아직 없으면
            byToken = new HashMap<String, String>(); // 새 Map을 만들고
            depthIndex.put(depth, byToken); // depth에 매단다.
        }
        byToken.put(itemOrNull, pointer); // 이 depth의 item 토큰으로 포인터를 역참조할 수 있게 저장한다.
    }
}
