package ai.chat2db.community.domain.api.enums.value;

import org.apache.commons.lang3.StringUtils;

import java.sql.Types;
import java.util.Locale;

public enum LargeValueTypeEnum {
    TEXT("TEXT"),
    JSON("JSON"),
    BINARY("BINARY"),
    IMAGE("IMAGE"),
    UNKNOWN("UNKNOWN");

    private final String code;

    LargeValueTypeEnum(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public boolean isBinaryLike() {
        return this == BINARY || this == IMAGE;
    }

    public boolean isTextLike() {
        return this == TEXT || this == JSON;
    }

    public boolean canBeLarge() {
        return this != UNKNOWN;
    }

    public static LargeValueTypeEnum fromCode(String code) {
        if (StringUtils.isBlank(code)) {
            return UNKNOWN;
        }
        try {
            return valueOf(code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }

    public static LargeValueTypeEnum resolve(String columnType, int sqlType) {
        String normalized = StringUtils.defaultString(columnType).toUpperCase(Locale.ROOT);
        if (normalized.contains("JSON")) {
            return JSON;
        }
        if (normalized.contains("IMAGE")) {
            return IMAGE;
        }
        if (normalized.contains("BLOB") || normalized.contains("BYTEA") || normalized.contains("BINARY")
                || normalized.contains("RAW")) {
            return BINARY;
        }
        if (normalized.contains("TEXT") || normalized.contains("CLOB") || normalized.contains("CHAR")) {
            return TEXT;
        }
        return switch (sqlType) {
            case Types.CLOB, Types.NCLOB, Types.LONGVARCHAR, Types.LONGNVARCHAR,
                    Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR -> TEXT;
            case Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> BINARY;
            default -> UNKNOWN;
        };
    }

    public static boolean isPotentialLargeType(String columnType, int sqlType) {
        return resolve(columnType, sqlType).canBeLarge();
    }

    /**
     * Resolves the type of a large value while it is being read back from a result snapshot.
     * <p>
     * The type recorded when the value was captured wins; column metadata is only a fallback, and a value whose type
     * cannot be determined at all is treated as text. The nullable {@code sqlType} keeps callers that have no JDBC
     * metadata at hand from having to special case it.
     */
    public static LargeValueTypeEnum resolveForRead(String columnType, Integer sqlType, String preferredType) {
        LargeValueTypeEnum type = fromCode(preferredType);
        if (type == UNKNOWN) {
            type = resolve(columnType, sqlType == null ? Types.OTHER : sqlType);
        }
        if (type == IMAGE) {
            return IMAGE;
        }
        if (type.isTextLike()) {
            return type;
        }
        return type == UNKNOWN ? TEXT : type;
    }

    public LargeValueTypeEnum withDetectedBinaryContent(BinaryContentTypeEnum binaryContentType) {
        if (this == BINARY && binaryContentType != null && binaryContentType.isImage()) {
            return IMAGE;
        }
        return this;
    }
}
