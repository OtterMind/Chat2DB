package ai.chat2db.plugin.mysql.enums.type;

import ai.chat2db.community.domain.api.model.metadata.Collation;

import java.util.Arrays;
import java.util.List;

public enum MysqlCollationEnum {

    UTF8_GENERAL_CI("utf8_general_ci"),

    UTF8MB4_GENERAL_CI("utf8mb4_general_ci"),

    BIG5_CHINESE_CI("big5_chinese_ci"),
    DEC8_SWEDISH_CI("dec8_swedish_ci"),

    HP8_ENGLISH_CI("hp8_english_ci"),
    KOI8R_GENERAL_CI("koi8r_general_ci"),
    LATIN1_SWEDISH_CI("latin1_swedish_ci"),
    LATIN2_GENERAL_CI("latin2_general_ci"),
    SWE7_SWEDISH_CI("swe7_swedish_ci"),
    ASCII_GENERAL_CI("ascii_general_ci"),
    UJIS_JAPANESE_CI("ujis_japanese_ci"),
    SJIS_JAPANESE_CI("sjis_japanese_ci"),
    HEBREW_GENERAL_CI("hebrew_general_ci"),
    TIS620_THAI_CI("tis620_thai_ci"),
    EUCKR_KOREAN_CI("euckr_korean_ci"),
    KOI8U_GENERAL_CI("koi8u_general_ci"),
    GB2312_CHINESE_CI("gb2312_chinese_ci"),
    GREEK_GENERAL_CI("greek_general_ci"),
    CP1250_GENERAL_CI("cp1250_general_ci"),
    GBK_CHINESE_CI("gbk_chinese_ci"),
    LATIN5_TURKISH_CI("latin5_turkish_ci"),
    ARMSCII8_GENERAL_CI("armscii8_general_ci"),
    CP1250_CZECH_CS("cp1250_czech_cs"),
    UCS2_GENERAL_CI("ucs2_general_ci"),
    CP866_GENERAL_CI("cp866_general_ci"),
    KEYBCS2_GENERAL_CI("keybcs2_general_ci"),
    MACCE_GENERAL_CI("macce_general_ci"),
    MACROMAN_GENERAL_CI("macroman_general_ci"),
    CP852_GENERAL_CI("cp852_general_ci"),
    LATIN7_GENERAL_CI("latin7_general_ci"),
    CP1251_GENERAL_CI("cp1251_general_ci"),
    UTF16_GENERAL_CI("utf16_general_ci"),
    UTF16LE_GENERAL_CI("utf16le_general_ci"),
    CP1256_GENERAL_CI("cp1256_general_ci"),
    CP1257_GENERAL_CI("cp1257_general_ci"),
    UTF32_GENERAL_CI("utf32_general_ci"),
    BINARY("binary"),
    GEOSTD8_GENERAL_CI("geostd8_general_ci"),
    CP932_JAPANESE_CI("cp932_japanese_ci"),
    EUCJPMS_JAPANESE_CI("eucjpms_japanese_ci"),
    GB18030_CHINESE_CI("gb18030_chinese_ci"),
    ;
    private final Collation collation;

    MysqlCollationEnum(String collationName) {
        this.collation = new Collation(collationName);
    }

    public Collation getCollation() {
        return collation;
    }

    public static List<Collation> getCollations() {
        return Arrays.stream(MysqlCollationEnum.values()).map(MysqlCollationEnum::getCollation).collect(java.util.stream.Collectors.toList());
    }

}
