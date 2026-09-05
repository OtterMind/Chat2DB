package ai.chat2db.plugin.mariadb;


import ai.chat2db.community.domain.api.model.metadata.CheckConstraintInfo;
import ai.chat2db.plugin.mariadb.value.MariaDBValueProcessor;
import ai.chat2db.plugin.mysql.MysqlMetaData;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IValueProcessor;
import ai.chat2db.spi.model.request.TableMetadataRequest;

import java.sql.Connection;
import java.util.List;

public class MariaDBMetaData extends MysqlMetaData implements IDbMetaData {

    @Override
    public List<CheckConstraintInfo> checkConstraints(Connection connection, TableMetadataRequest request) {
        return List.of();
    }

    @Override
    public IValueProcessor getValueProcessor() {
        return new MariaDBValueProcessor();
    }
}
