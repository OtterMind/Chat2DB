package ai.chat2db.plugin.gbase8s;

import ai.chat2db.plugin.generic.GenericMetaData;
import ai.chat2db.plugin.gbase8s.builder.GBase8sSqlBuilder;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISqlBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.stream.Collectors;

@Slf4j
public class GBase8sMetaData extends GenericMetaData implements IDbMetaData {

    @Override
    public ISqlBuilder getSqlBuilder() {
        return new GBase8sSqlBuilder();
    }

    @Override
    public String getMetaDataName(String... names) {
        if (names == null || names.length == 0) {
            return "";
        }
        if (names.length == 1) {
            return joinNonBlank("", names[0]);
        }
        if (names.length == 2) {
            return joinNonBlank(":", names[0], names[1]);
        }

        String ownerAndTable = joinNonBlank(".", names[names.length - 2], names[names.length - 1]);
        return joinNonBlank(":", names[0], ownerAndTable);
    }

    private static String joinNonBlank(String separator, String... names) {
        return Arrays.stream(names)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.joining(separator));
    }
}
