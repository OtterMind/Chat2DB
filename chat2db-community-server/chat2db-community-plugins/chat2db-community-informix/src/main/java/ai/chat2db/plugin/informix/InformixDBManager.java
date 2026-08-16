package ai.chat2db.plugin.informix;

import ai.chat2db.plugin.generic.GenericDBManager;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;

@Slf4j
public class InformixDBManager extends GenericDBManager implements IDbManager {

    @Override
    public Connection getConnection(ConnectInfo connectInfo) {
        String url = connectInfo.getUrl();
        String service = connectInfo.getServiceName();
        if (StringUtils.isNotBlank(service) && !StringUtils.contains(url, "INFORMIXSERVER=")) {
            connectInfo.setUrl(url + ":" + "INFORMIXSERVER=" + service);
        }
        return super.getConnection(connectInfo);
    }
}
