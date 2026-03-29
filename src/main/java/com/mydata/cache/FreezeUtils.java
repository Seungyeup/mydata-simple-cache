package com.mydata.cache; // 패키지 경로를 선언한다.

import java.util.Collections; // 불변 Map 래핑을 위해 가져온다.
import java.util.HashMap; // 방어적 복사를 위해 가져온다.
import java.util.Map; // Map 타입을 사용한다.

final class FreezeUtils { // 패키지 내부에서만 쓰는 유틸이므로 package-private + final로 둔다.

    private FreezeUtils() { // 유틸 클래스는 인스턴스 생성을 막는다.
    }

    static Map<String, String> freezeStringMap(Map<String, String> source) { // Map<String,String>을 방어적 복사 + 불변화한다.
        if (source == null || source.isEmpty()) { // null/빈 Map이면
            return Collections.<String, String>emptyMap(); // 빈 불변 Map을 반환한다.
        }
        return Collections.unmodifiableMap(new HashMap<String, String>(source)); // 복사본을 불변으로 감싸 반환한다.
    }
}
