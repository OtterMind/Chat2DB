package ai.chat2db.community.domain.api.model.operation;


import com.google.common.collect.Lists;
import lombok.Data;
import org.apache.commons.collections4.CollectionUtils;

import java.util.ArrayList;
import java.util.List;


@Data
public class Operation {


    private Long id;


    private String name;


    private Long dataSourceId;


    private String dataSourceName;


    private Boolean connectable;


    private String databaseName;


    private String schemaName;


    private Boolean nameCustomized;


    private String type;


    private String ddl;


    private String status;


    private String tabOpened;


    private String operationType;


    public List<Operation> select(List<Operation> operations, int pageNo, int pageSize) {
        if(CollectionUtils.isEmpty(operations)){
            return Lists.newArrayList();
        }
        List<Operation> result = new ArrayList<>();
        long start = ((long) pageNo - 1L) * pageSize;
        long n = 0L;
        for (int index = operations.size() - 1; index >= 0; index--) {
            Operation o = operations.get(index);
            if (select(o)) {
                if (n >= start) {
                    result.add(o);
                }
                n++;
            }
            if (result.size() >= pageSize) {
                return result;
            }
        }
        return result;
    }

    private boolean select(Operation operation) {
        if (dataSourceId != null ? !dataSourceId.equals(operation.dataSourceId) : false)
            return false;
        if (databaseName != null ? !databaseName.equals(operation.databaseName) : false)
            return false;
        if (schemaName != null ? !schemaName.equals(operation.schemaName) : false)
            return false;
        if (tabOpened != null ? !tabOpened.equals(operation.tabOpened) : false)
            return false;
        if (status != null ? !status.equals(operation.status) : false)
            return false;
        return true;
    }
}
