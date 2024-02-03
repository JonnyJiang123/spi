package org.mj.spi.util;

import java.util.Map;

public class MapUtils {
    public static boolean isEmpty(final Map<?,?> map) {
        return map == null || map.isEmpty();
    }
}
