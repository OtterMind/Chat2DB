
package ai.chat2db.spi.util;

import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Slf4j
public class ResultSetUtils {


    public static List<String> getRsHeader(ResultSet rs) {
        try {
            ResultSetMetaData resultSetMetaData = rs.getMetaData();
            int col = resultSetMetaData.getColumnCount();
            List<String> headerList = Lists.newArrayListWithExpectedSize(col);
            for (int i = 1; i <= col; i++) {
                headerList.add(getColumnName(resultSetMetaData, i));
            }
            return headerList;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }


    public static <T> List<T> toObjectList(ResultSet rs, Class<T> clazz) {
        try {
            if (rs == null || clazz == null) {
                return Lists.newArrayList();
            }
            List<T> list = Lists.newArrayList();
            ResultSetMetaData rsMetaData = rs.getMetaData();
            if(rsMetaData == null){
                return list;
            }
            int col = rsMetaData.getColumnCount();
            List<String> headerList = getRsHeader(rs);
            while (rs.next()) {
                Map<String, Object> map = new HashMap<>();
                for (int i = 1; i <= col; i++) {
                    map.put(headerList.get(i - 1), readMetadataValue(rs, i));
                }
                T obj = ResultSetConverter.map2object(map, clazz);

                list.add(obj);
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * getObject on some drivers yields vendor structs the metadata models cannot
     * absorb (IoTDB returns tsfile Binary for every DatabaseMetaData column);
     * fall back to the string form JDBC guarantees for those values.
     */
    private static Object readMetadataValue(ResultSet rs, int index) throws SQLException {
        Object value = rs.getObject(index);
        if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof java.util.Date
                || value instanceof byte[]) {
            return value;
        }
        return rs.getString(index);
    }

    public static String getColumnName(ResultSetMetaData resultSetMetaData, int column) throws SQLException {
        String columnLabel = resultSetMetaData.getColumnLabel(column);
        if (columnLabel != null) {
            return columnLabel;
        }
        return resultSetMetaData.getColumnName(column);
    }
    public static String getTableColumnName(ResultSetMetaData resultSetMetaData, int column) throws SQLException {
        return resultSetMetaData.getColumnName(column);
    }

    public static String getTableName(ResultSetMetaData resultSetMetaData, int column) throws SQLException {
        return resultSetMetaData.getTableName(column);
    }

    public static String getColumnDataTypeName(ResultSetMetaData resultSetMetaData, int columnIndex) {
        try {
            return resultSetMetaData.getColumnTypeName(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static int getColumnPrecision(ResultSetMetaData resultSetMetaData, int columnIndex) {
        try {
            return resultSetMetaData.getPrecision(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static int getColumnScale(ResultSetMetaData resultSetMetaData, int columnIndex) {
        try {
            return resultSetMetaData.getScale(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static String getString(ResultSet rs, int columnIndex) {
        try {
            Object obj = rs.getObject(columnIndex);
            if (obj == null) {
                return null;
            }
            if (obj instanceof String) {
                return (String) obj;
            } else if (obj instanceof BigDecimal bigDecimal) {
                return bigDecimal.toPlainString();
            } else if (obj instanceof Double d) {
                return BigDecimal.valueOf(d).toPlainString();
            } else if (obj instanceof Float f) {
                return BigDecimal.valueOf(f).toPlainString();
            } else if (obj instanceof Clob) {
                return largeString(rs, columnIndex);
            } else if (obj instanceof byte[]) {
                return largeString(rs, columnIndex);
            } else if (obj instanceof Blob blob) {
                return largeStringBlob(blob);
            } else if (obj instanceof Timestamp || obj instanceof LocalDateTime) {
                return largeTime(obj);
            } else if (obj instanceof SQLXML) {
                return ((SQLXML) obj).getString();
            } else {
                return obj.toString();
            }
        } catch (Exception e) {
            log.warn("Failed to parse number:{},", columnIndex, e);
            try {
                return rs.getString(columnIndex);
            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    private static String largeStringBlob(Blob blob) throws SQLException {
        if (blob == null) {
            return null;
        }
        int length = Math.toIntExact(blob.length());
        byte[] data = blob.getBytes(1, length);
        String result = new String(data, StandardCharsets.UTF_8);
        return result;
    }

    private static String largeTime(Object obj) throws SQLException {
        Object timeField = obj;

        LocalDateTime localDateTime;

        if (obj instanceof Timestamp) {
            localDateTime = ((Timestamp) timeField).toLocalDateTime();
        } else if (obj instanceof LocalDateTime) {
            localDateTime = (LocalDateTime) timeField;
        } else {
            try {
                localDateTime = LocalDateTime.parse(timeField.toString(), DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
            } catch (Exception e) {
                localDateTime = LocalDateTime.parse(timeField.toString(), DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
            }
        }
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String formattedDateTime = dtf.format(localDateTime);
        return formattedDateTime;
    }

    private static String largeString(ResultSet rs, int index) throws SQLException {
        String result = rs.getString(index);
        if (result == null) {
            return null;

        }
        return result;
    }

    public static InputStream getBinaryStream(ResultSet rs, int columnIndex) {
        try {
            return rs.getBinaryStream(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] getBytes(ResultSet rs, int columnIndex) {
        try {
            return rs.getBytes(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean getBoolean(ResultSet rs, int columnIndex) {
        try {
            return rs.getBoolean(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static int getInt(ResultSet resultSet, int columnIndex) {
        try {
            return resultSet.getInt(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static Date getDate(ResultSet resultSet, int columnIndex) {
        try {
            return resultSet.getDate(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static Timestamp getTimestamp(ResultSet resultSet, int columnIndex) {
        try {
            return resultSet.getTimestamp(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static Clob getClob(ResultSet resultSet, int columnIndex) {
        try {
            return resultSet.getClob(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static Blob getBlob(ResultSet resultSet, int columnIndex) {
        try {
            return resultSet.getBlob(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static BigDecimal getBigDecimal(ResultSet resultSet, int columnIndex) {
        try {
            return resultSet.getBigDecimal(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static String getStringValue(ResultSet resultSet, int columnIndex) {
        try {
            return resultSet.getString(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static Reader getCharacterStream(ResultSet resultSet, int columnIndex) {
        try {
            return resultSet.getCharacterStream(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static long getLong(ResultSet resultSet, int columnIndex) {
        try {
            return resultSet.getLong(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static float getFloat(ResultSet resultSet, int columnIndex) {
        try {
            return resultSet.getFloat(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static double getDouble(ResultSet resultSet, int columnIndex) {
        try {
            return resultSet.getDouble(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

}
