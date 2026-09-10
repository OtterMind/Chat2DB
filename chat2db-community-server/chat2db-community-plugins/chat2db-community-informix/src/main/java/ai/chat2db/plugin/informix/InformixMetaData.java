package ai.chat2db.plugin.informix;

import ai.chat2db.plugin.generic.GenericMetaData;
import ai.chat2db.plugin.informix.builder.InformixSqlBuilder;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISqlBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class InformixMetaData extends GenericMetaData implements IDbMetaData {

    @Override
    public ISqlBuilder getSqlBuilder() {
        // Informix rejects the default MySQL/PG-style RENAME/MODIFY/EXPLAIN syntax.
        return new InformixSqlBuilder();
    }

    @Override
    public String getMetaDataName(String... names) {
        List<String> validNames = Arrays.stream(names)
                .filter(name -> StringUtils.isNotBlank(name))
                .collect(Collectors.toList());

        int size = validNames.size();
        if (size == 0) return "";
        if (size == 1) return validNames.get(0);

        return validNames.get(size - 2) + "." + validNames.get(size - 1);
    }
}
