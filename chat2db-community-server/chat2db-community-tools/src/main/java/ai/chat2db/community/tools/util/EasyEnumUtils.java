package ai.chat2db.community.tools.util;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import ai.chat2db.community.tools.enums.IBaseEnum;


public class EasyEnumUtils {


    private static final Map<String, Map<?, IBaseEnum<?>>> ENUM_CACHE = new ConcurrentHashMap<>();


    public static <T extends IBaseEnum<?>> String getDescription(final Class<T> clazz, final String code) {
        if (clazz == null || code == null) {
            return null;
        }
        IBaseEnum<?> baseEnum = getEnum(clazz, code);
        if (baseEnum == null) {
            return null;
        }
        return baseEnum.getDescription();
    }


    public static <T extends IBaseEnum<?>> T getEnum(final Class<T> clazz, final String code) {
        if (clazz == null || code == null) {
            return null;
        }
        return getEnumMap(clazz).get(code);
    }


    public static <T extends IBaseEnum<?>> boolean isValidEnum(final Class<T> clazz, final String code) {
        return isValidEnum(clazz, code, true);
    }


    public static <T extends IBaseEnum<?>> boolean isValidEnum(final Class<T> clazz, final String code,
        final boolean ignoreNull) {
        if (code == null) {
            return ignoreNull;
        }
        if (clazz == null) {
            return false;
        }
        return getEnumMap(clazz).containsKey(code);
    }


    public static <T extends IBaseEnum<?>> Map<String, T> getEnumMap(final Class<T> clazz) {
        if (clazz == null) {
            return java.util.Collections.emptyMap();
        }
        String className = clazz.getName();
        Map<?, IBaseEnum<?>> result = ENUM_CACHE.computeIfAbsent(className, value -> {
            T[] baseEnums = clazz.getEnumConstants();
            if (baseEnums == null) {
                return java.util.Collections.emptyMap();
            }
            return Arrays.stream(baseEnums)
                .collect(Collectors.toMap(IBaseEnum::getCode, Function.identity(), (e1, e2) -> e1));
        });
        return (Map)result;
    }
}
