package ai.chat2db.community.domain.core.impl.task.export.excel;

import ai.chat2db.community.domain.api.enums.ExportFileSuffixEnum;
import ai.chat2db.community.domain.core.impl.db.extension.SqlExecutionPolicyManager;
import ai.chat2db.community.domain.core.impl.task.export.ExportCellProcessorChain;
import com.alibaba.excel.support.ExcelTypeEnum;
import org.springframework.stereotype.Component;


@Component
public class CsvDataExporter extends BaseExcelExporter {


    public CsvDataExporter(ExportCellProcessorChain exportCellProcessorChain,
            SqlExecutionPolicyManager sqlExecutionPolicyManager) {
        super(exportCellProcessorChain, sqlExecutionPolicyManager);
        this.contentType = "text/csv";
        this.suffix = ExportFileSuffixEnum.CSV.getSuffix();
    }

    @Override
    public String type() {
        return "csv";
    }


    @Override
    protected ExcelTypeEnum getExcelType() {
        return ExcelTypeEnum.CSV;
    }
}
