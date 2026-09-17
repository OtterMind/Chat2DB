package ai.chat2db.community.domain.core.impl.task.imports.json;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.task.JsonOptions;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.task.imports.BaseImporter;
import ai.chat2db.community.domain.core.impl.task.imports.ImportRowSqlBuilder;
import ai.chat2db.community.domain.core.impl.task.imports.ImportSqlExecutor;
import ai.chat2db.community.domain.core.impl.task.imports.reader.ImportCell;
import ai.chat2db.community.domain.core.impl.task.imports.reader.JsonImportReader;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class JSONImporter extends BaseImporter {
    @Override
    protected void doImportData(ImportTaskSpec spec, TaskExecutionContext context, List<TableColumn> columns) {
        JsonOptions options = (spec.getJsonOptions() == null ? new JsonOptions() : spec.getJsonOptions()).validate();
        ImportRowSqlBuilder builder = new ImportRowSqlBuilder(spec, columns);
        ImportSqlExecutor executor = new ImportSqlExecutor(context);
        Map<Integer, String> header = new LinkedHashMap<>();
        List<String> names = spec.getColumnMappings() == null ? columns.stream().map(TableColumn::getName).toList()
                : spec.getColumnMappings().stream().map(mapping -> mapping.getSourceColumn()).toList();
        for (int index = 0; index < names.size(); index++) header.put(index, names.get(index));
        builder.acceptHead(header);
        List<String> batch = new ArrayList<>(BATCH_SIZE);
        JsonImportReader.read(new File(spec.getSourceFile()), options, Integer.MAX_VALUE, (record, rowNumber) -> {
            Map<Integer, ImportCell> cells = new LinkedHashMap<>();
            header.forEach((index, name) -> cells.put(index, record.get(name)));
            batch.add(builder.buildCells(cells, rowNumber));
            if (batch.size() >= BATCH_SIZE) {
                executor.executeBatch(batch);
                batch.clear();
            }
        }, context::checkCancelled);
        executor.executeBatch(batch);
    }
}
