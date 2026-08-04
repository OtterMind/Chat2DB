package ai.chat2db.community.tools.util;

import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import jakarta.validation.constraints.NotNull;
import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.stream.Collectors;


public class EasyStringUtils {


    private static final char ZERO_CHAR = '0';


    public static String cutUserId(String userId) {
        if (!org.apache.commons.lang3.StringUtils.isNumeric(userId)) {
            return userId;
        }
        int startIndex = 0;
        for (int i = 0; i < userId.length(); i++) {
            char c = userId.charAt(i);
            if (ZERO_CHAR == c) {
                startIndex = i + 1;
            } else {
                break;
            }
        }
        if (startIndex == userId.length()) {
            return "0";
        }
        return userId.substring(startIndex);
    }


    public static String cutName(String name, String workNo) {
        if (StringUtils.isBlank(workNo) || StringUtils.isBlank(name)) {
            return name;
        }
        String cutName = StringUtils.removeStart(name, workNo);
        int lastIndex = cutName.length();
        for (int i = cutName.length() - 1; i >= 0; i--) {
            char c = cutName.charAt(i);
            if (ZERO_CHAR == c) {
                lastIndex = i;
            } else {
                break;
            }
        }
        return cutName.substring(0, lastIndex);
    }


    public static String padUserId(String userId) {
        if (!StringUtils.isNumeric(userId)) {
            return userId;
        }
        return StringUtils.leftPad(userId, 6, '0');
    }


    public static String buildShowName(String name, String nickName) {
        StringBuilder showName = new StringBuilder();
        if (StringUtils.isNotBlank(name)) {
            showName.append(name);
        }
        if (StringUtils.isNotBlank(nickName)) {
            showName.append("(");
            showName.append(nickName);
            showName.append(")");
        }
        return showName.toString();
    }


    public static String join(CharSequence delimiter, CharSequence... elements) {
        if (elements == null) {
            return null;
        }
        List<CharSequence> charSequenceList = Arrays.stream(elements).filter(
                org.apache.commons.lang3.StringUtils::isNotBlank).collect(Collectors.toList());
        if (charSequenceList.isEmpty()) {
            return null;
        }
        return String.join(delimiter, charSequenceList);
    }


    public static String limitString(String str, int length) {
        if (Objects.isNull(str)) {
            return null;
        }
        String limitString = StringUtils.substring(str, 0, length);
        if (str.length() > length) {
            limitString += "...";
        }
        return limitString;
    }


    public static String escapeString(@NotNull String str, Map<Character, Character> escapeMap) {
        if (str == null) {
            return null;
        }
        if (StringUtils.isBlank(str)) {
            return str;
        }


        StringBuilder escapedString = new StringBuilder(str.length() * 2);

        for (char c : str.toCharArray()) {
            if (escapeMap.containsKey(c)) {
                escapedString.append(escapeMap.get(c)).append(c);
            } else {
                escapedString.append(c);
            }
        }
        return escapedString.toString();
    }

    public static String escapeString(String str) {
        HashMap<Character, Character> escapeMap = Maps.newHashMapWithExpectedSize(2);
        escapeMap.put((char) 39, (char) 39);
        escapeMap.put((char) 92, (char) 92);
        return escapeString(str, escapeMap);
    }


    public static String escapeAndQuoteString(String value) {
        return quoteString(escapeString(value));
    }


    public static String quoteString(String value, char quoteChar) {
        return quoteChar + value + quoteChar;
    }


    public static String quoteString(String value) {
        return quoteString(value, (char) 39);
    }

    public static String getBitString(byte[] bytes, final int precision) {
        if (bytes == null || bytes.length == 0 || precision <= 0) {
            return "";
        }

        StringBuilder builder = new StringBuilder(precision);
        for (byte b : bytes) {
            builder.append(Strings.padStart(Integer.toBinaryString(b & 0xFF), 8, '0'));
        }
        String bitString = builder.toString();
        if (bitString.length() > precision) {
            return bitString.substring(bitString.length() - precision);
        }
        return Strings.padStart(bitString, precision, '0');
    }


    public static String escapeLineString(String str) {
        if (StringUtils.isBlank(str)) {
            return str;
        }
        return str;
    }
    public static String sqlEscape(String str) {
       if(StringUtils.isBlank(str)){
           return str;
       }
       str = str.trim();
       if(str.endsWith(";")){
           str = str.substring(0, str.length() - 1);
       }
       return str;
    }

}
