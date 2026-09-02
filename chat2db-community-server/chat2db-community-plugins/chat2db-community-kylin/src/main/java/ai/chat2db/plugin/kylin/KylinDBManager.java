package ai.chat2db.plugin.kylin;

import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.DefaultDBManager;

public class KylinDBManager extends DefaultDBManager implements IDbManager {
    @Override
    public ai.chat2db.spi.model.export.ExportCapability getExportCapability() {
        return ai.chat2db.spi.model.export.ExportCapability.STREAMING_ONLY;
    }


}
