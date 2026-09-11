package ai.chat2db.community.domain.core.impl.task.export.excel;

import ai.chat2db.community.domain.api.enums.ExportFileSuffixEnum;
import ai.chat2db.community.domain.core.impl.db.extension.SqlExecutionPolicyManager;
import ai.chat2db.community.domain.core.impl.task.export.ExportCellProcessorChain;
import com.alibaba.excel.support.ExcelTypeEnum;
import org.springframework.stereotype.Component;


@Component
public class XlsxDataExporter extends BaseExcelExporter {

    public XlsxDataExporter(ExportCellProcessorChain exportCellProcessorChain,
            SqlExecutionPolicyManager sqlExecutionPolicyManager) {
        super(exportCellProcessorChain, sqlExecutionPolicyManager);
        this.suffix = ExportFileSuffixEnum.EXCEL.getSuffix();
        this.contentType="application/vnd.ms-excel";
    }

    @Override
    public String type() {
        return "xlsx";
    }


    @Override
    protected ExcelTypeEnum getExcelType() {
        return ExcelTypeEnum.XLSX;
    }
}
