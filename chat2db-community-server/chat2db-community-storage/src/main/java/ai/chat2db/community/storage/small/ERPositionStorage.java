package ai.chat2db.community.storage.small;

import ai.chat2db.community.domain.api.model.er.ERPosition;
import cn.hutool.core.util.ObjectUtil;

import java.io.File;
import java.util.List;

public class ERPositionStorage extends SmallDataStorage<ERPosition> {

    private static class Holder {
        private static final ERPositionStorage INSTANCE = new ERPositionStorage();
    }

    public static ERPositionStorage getInstance() {
        return Holder.INSTANCE;
    }

    protected ERPositionStorage() {
        super("er_position", ERPosition.class);
    }

    ERPositionStorage(File storageFile) {
        super(storageFile, ERPosition.class);
    }

    public String getPosition(Long dataSourceId, String databaseName, String schemaName) {
        List<ERPosition> list = super.getDataList();
        for (ERPosition param : list) {
            if (param.getDataSourceId().equals(dataSourceId) &&
                    ObjectUtil.equals(param.getDatabaseName(), databaseName)
                    && ObjectUtil.equals(param.getSchemaName(), schemaName)) {
                return param.getPosition();
            }
        }
        return null;
    }

    public void savePosition(ERPosition param) {
        List<ERPosition> list = super.getDataList();
        for (ERPosition p : list) {
            if (p.getDataSourceId().equals(param.getDataSourceId()) &&
                    ObjectUtil.equals(p.getDatabaseName(), param.getDatabaseName())
                    && ObjectUtil.equals(p.getSchemaName(), param.getSchemaName())) {
                p.setPosition(param.getPosition());
                update(p);
                return;
            }
        }
        super.save(param);
    }
}
