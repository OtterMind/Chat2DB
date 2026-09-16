package ai.chat2db.community.storage.small;

import ai.chat2db.community.domain.api.model.er.ERPosition;
import cn.hutool.core.util.ObjectUtil;

import java.io.File;
import java.util.ArrayList;
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

    public synchronized void savePosition(ERPosition param) {
        if (param == null) {
            return;
        }
        List<ERPosition> candidateList = new ArrayList<>(dataMap.size() + 1);
        ERPosition replacement = null;
        for (ERPosition current : dataMap.values()) {
            ERPosition candidate = copy(current);
            if (replacement == null && samePositionKey(current, param)) {
                candidate.setPosition(param.getPosition());
                replacement = candidate;
            }
            candidateList.add(candidate);
        }
        boolean inserted = replacement == null;
        if (inserted) {
            replacement = copy(param);
            if (replacement.getId() == null) {
                replacement.setId(generateId());
            }
            candidateList.add(replacement);
        }

        saveDataList(candidateList);
        dataMap.put(replacement.getId(), replacement);
        if (inserted) {
            param.setId(replacement.getId());
        }
    }

    private boolean samePositionKey(ERPosition current, ERPosition update) {
        return current.getDataSourceId().equals(update.getDataSourceId())
                && ObjectUtil.equals(current.getDatabaseName(), update.getDatabaseName())
                && ObjectUtil.equals(current.getSchemaName(), update.getSchemaName());
    }

    private ERPosition copy(ERPosition source) {
        ERPosition copy = new ERPosition();
        copy.setId(source.getId());
        copy.setDataSourceId(source.getDataSourceId());
        copy.setDatabaseName(source.getDatabaseName());
        copy.setSchemaName(source.getSchemaName());
        copy.setPosition(source.getPosition());
        return copy;
    }
}
