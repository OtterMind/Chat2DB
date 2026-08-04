package ai.chat2db.community.domain.api.model.pin;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Objects;

@Data
public class PinTable {

    private Long id;


    @NotNull
    private Long dataSourceId;


    private String databaseName;


    private String schemaName;


    private String tableName;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        PinTable pinTable = (PinTable) o;

        if (dataSourceId != null ? !dataSourceId.equals(pinTable.dataSourceId) : pinTable.dataSourceId != null)
            return false;
        if (databaseName != null ? !databaseName.equals(pinTable.databaseName) : pinTable.databaseName != null)
            return false;
        if (schemaName != null ? !schemaName.equals(pinTable.schemaName) : pinTable.schemaName != null) return false;
        return tableName != null ? tableName.equals(pinTable.tableName) : pinTable.tableName == null;
    }

    @Override
    public int hashCode() {
        return Objects.hash(dataSourceId, databaseName, schemaName, tableName);
    }

    public boolean select(PinTable pinTable) {
        if (dataSourceId != null ? !dataSourceId.equals(pinTable.dataSourceId) : pinTable.dataSourceId != null)
            return false;
        if (databaseName != null ? !databaseName.equals(pinTable.databaseName) : pinTable.databaseName != null)
            return false;
        if (schemaName != null ? !schemaName.equals(pinTable.schemaName) : pinTable.schemaName != null) return false;
        return true;
    }

}
