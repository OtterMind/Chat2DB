package ai.chat2db.community.domain.core.impl.task.export;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SqlValueSerializer {

    private SqlValueSerializer() {
    }

    public static String toSqlLiteral(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return "0x" + HexFormat.of().withUpperCase().formatHex(bytes);
        }
        if (value instanceof char[] chars) {
            return new String(chars);
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<String> values = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                values.add(toSqlLiteral(Array.get(value, index)));
            }
            return values.toString();
        }
        if (value instanceof Collection<?> values) {
            return values.stream().map(SqlValueSerializer::toSqlLiteral).toList().toString();
        }
        if (value instanceof Map<?, ?> values) {
            Map<String, String> serialized = new LinkedHashMap<>(values.size());
            values.forEach((key, mapValue) -> serialized.put(toSqlLiteral(key), toSqlLiteral(mapValue)));
            return serialized.toString();
        }
        return String.valueOf(value);
    }
}
