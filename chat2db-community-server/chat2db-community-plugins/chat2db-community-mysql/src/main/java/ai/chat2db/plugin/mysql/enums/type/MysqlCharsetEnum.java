package ai.chat2db.plugin.mysql.enums.type;

import ai.chat2db.community.domain.api.model.metadata.Charset;

import java.util.Arrays;
import java.util.List;

public enum MysqlCharsetEnum {

    UTF8("utf8", "utf8_general_ci"),
    UTF8MB3("utf8mb3", "utf8mb3_general_ci"),
    BIG5("big5", "big5_chinese_ci"),
    DEC8("dec8", "dec8_swedish_ci"),
    CP850("cp850", "cp850_general_ci"),
    HP8("hp8", "hp8_english_ci"),
    KOI8R("koi8r", "koi8r_general_ci"),
    LATIN1("latin1", "latin1_swedish_ci"),
    LATIN2("latin2", "latin2_general_ci"),
    SWE7("swe7", "swe7_swedish_ci"),
    ASCII("ascii", "ascii_general_ci"),
    UJIS("ujis", "ujis_japanese_ci"),
    SJIS("sjis", "sjis_japanese_ci"),
    HEBREW("hebrew", "hebrew_general_ci"),
    TIS620("tis620", "tis620_thai_ci"),
    EUCKR("euckr", "euckr_korean_ci"),
    KOI8U("koi8u", "koi8u_general_ci"),
    GB2312("gb2312", "gb2312_chinese_ci"),
    GREEK("greek", "greek_general_ci"),
    CP1250("cp1250", "cp1250_general_ci"),
    GBK("gbk", "gbk_chinese_ci"),
    LATIN5("latin5", "latin5_turkish_ci"),
    ARMSCII8("armscii8", "armscii8_general_ci"),
    UCS2("ucs2", "ucs2_general_ci"),
    CP866("cp866", "cp866_general_ci"),
    KEYBCS2("keybcs2", "keybcs2_general_ci"),
    MACCE("macce", "macce_general_ci"),
    MACROMAN("macroman", "macroman_general_ci"),
    CP852("cp852", "cp852_general_ci"),
    LATIN7("latin7", "latin7_general_ci"),
    UTF8MB4("utf8mb4", "utf8mb4_general_ci"),
    CP1251("cp1251", "cp1251_general_ci"),
    UTF16("utf16", "utf16_general_ci"),
    UTF16LE("utf16le", "utf16le_general_ci"),
    CP1256("cp1256", "cp1256_general_ci"),
    CP1257("cp1257", "cp1257_general_ci"),
    UTF32("utf32", "utf32_general_ci"),
    BINARY("binary", "binary"),
    GEOSTD8("geostd8", "geostd8_general_ci"),
    CP932("cp932", "cp932_japanese_ci"),
    EUCJPMS("eucjpms", "eucjpms_japanese_ci"),
    GB18030("gb18030", "gb18030_chinese_ci");
    private final Charset charset;

    MysqlCharsetEnum(String charsetName, String defaultCollationName) {
        this.charset = new Charset(charsetName, defaultCollationName);
    }

    public Charset getCharset() {
        return charset;
    }

    public static List<Charset> getCharsets() {
        return Arrays.stream(MysqlCharsetEnum.values()).map(MysqlCharsetEnum::getCharset).collect(java.util.stream.Collectors.toList());
    }

}
