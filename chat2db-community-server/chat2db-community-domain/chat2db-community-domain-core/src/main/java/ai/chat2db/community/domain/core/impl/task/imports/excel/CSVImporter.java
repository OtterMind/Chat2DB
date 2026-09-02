package ai.chat2db.community.domain.core.impl.task.imports.excel;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.task.imports.BaseImporter;
import ai.chat2db.community.domain.core.impl.task.imports.ImportColumnResolver;
import ai.chat2db.community.domain.core.impl.task.imports.ImportFileProbe;
import ai.chat2db.community.domain.core.impl.task.imports.ImportRowBatcher;
import ai.chat2db.community.domain.core.impl.task.imports.IImportStrategy;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.File;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

/**
 * CSV import on commons-csv: the real grammar (quotes, embedded newlines, BOM, configurable
 * delimiter and charset) replaces the EasyExcel sheet reader, which additionally chunked input at
 * the Excel row limit.
 */
public class CSVImporter extends BaseImporter implements IImportStrategy {

    @Override
    protected void doImportData(ImportTaskSpec spec, TaskExecutionContext context,
            List<TableColumn> columns) throws Exception {
        File source = new File(spec.getSourceFile());
        Charset charset = ImportFileProbe.effectiveCharset(source,
                spec.getOptions() == null ? null : spec.getOptions().getCharset());
        char quote = ImportFileProbe.quoteChar(
                spec.getOptions() == null ? null : spec.getOptions().getQuoteChar());
        char delimiter = ImportFileProbe.delimiterChar(
                spec.getOptions() == null ? null : spec.getOptions().getDelimiter(), charset, source);
        CSVFormat format = ImportFileProbe.csvFormat(delimiter, quote);
        int skipRows = spec.getOptions() == null || spec.getOptions().getSkipRows() == null
                ? 0 : Math.max(0, spec.getOptions().getSkipRows());

        try (CSVParser parser = ImportFileProbe.openParser(source, charset, format)) {
            var iterator = parser.iterator();
            if (!iterator.hasNext()) {
                return;
            }
            ImportColumnResolver.Resolution resolution =
                    ImportColumnResolver.resolve(columns, iterator.next().toList(), spec.getOptions());
            reportResolution(context, resolution);
            try (ImportRowBatcher batcher = new ImportRowBatcher(spec, context, resolution,
                    Chat2DBContext.getDbMetaData().getValueProcessor())) {
                long rowNumber = 1;
                int skipped = 0;
                while (iterator.hasNext()) {
                    context.checkCancelled();
                    CSVRecord record = iterator.next();
                    if (skipped < skipRows) {
                        skipped++;
                        continue;
                    }
                    batcher.accept(rowNumber++, record.toList());
                }
                batcher.flush();
                context.logInfo("IMPORT_SUMMARY", "CSV import finished", Map.of(
                        "importedRows", batcher.importedRows(),
                        "rejectedRows", batcher.rejectedRows()));
            }
        }
    }
}
