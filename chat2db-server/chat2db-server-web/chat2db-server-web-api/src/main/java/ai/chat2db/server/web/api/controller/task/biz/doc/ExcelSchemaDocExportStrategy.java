package ai.chat2db.server.web.api.controller.task.biz.doc;

import ai.chat2db.server.domain.api.enums.ExportTypeEnum;
import ai.chat2db.server.domain.api.model.TableParameter;
import ai.chat2db.server.domain.api.model.ForeignKeyInfo;
import ai.chat2db.server.tools.common.util.I18nUtils;
import ai.chat2db.server.web.api.controller.rdb.doc.adaptive.CustomCellWriteHeightConfig;
import ai.chat2db.server.web.api.controller.rdb.doc.adaptive.CustomCellWriteWidthConfig;
import ai.chat2db.server.web.api.controller.rdb.doc.merge.MyMergeExcel;
import ai.chat2db.server.web.api.controller.rdb.doc.style.CustomExcelStyle;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.write.style.HorizontalCellStyleStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ExcelSchemaDocExportStrategy extends AbstractSchemaDocExportStrategy {

    @Override
    public boolean supports(String exportType) {
        return ExportTypeEnum.EXCEL.getCode().equals(exportType);
    }

    @Override
    protected void doExport(OutputStream outputStream, SchemaDocExportContext context) throws Exception {
        List<TableParameter> export = new ArrayList<>();
        for (Map.Entry<String, List<TableParameter>> item : context.getTableParameterMap().entrySet()) {
            TableParameter t = new TableParameter();
            t.setNo(item.getKey()).setColumnComment(MyMergeExcel.NAME);
            export.add(t);
            export.addAll(item.getValue());
        }

        com.alibaba.excel.ExcelWriter excelWriter = EasyExcel.write(outputStream)
                .registerWriteHandler(new HorizontalCellStyleStrategy(CustomExcelStyle.getHeadStyle(), CustomExcelStyle.getContentWriteCellStyle()))
                .registerWriteHandler(new CustomCellWriteHeightConfig())
                .registerWriteHandler(new CustomCellWriteWidthConfig())
                .registerWriteHandler(new MyMergeExcel())
                .build();

        com.alibaba.excel.write.metadata.WriteSheet writeSheet = EasyExcel.writerSheet(I18nUtils.getMessage("main.sheetName"))
                .head(TableParameter.class)
                .build();
        excelWriter.write(export, writeSheet);

        List<ForeignKeyInfo> foreignKeyList = context.getForeignKeyList();
        if (foreignKeyList != null && !foreignKeyList.isEmpty()) {
            List<List<String>> fkData = new ArrayList<>();
            List<String> header = new ArrayList<>();
            header.add(I18nUtils.getMessage("workspace.tableRelation.masterTable"));
            header.add(I18nUtils.getMessage("workspace.tableRelation.uniqueColumn"));
            header.add(I18nUtils.getMessage("workspace.tableRelation.childTable"));
            header.add(I18nUtils.getMessage("workspace.tableRelation.relationColumn"));
            header.add(I18nUtils.getMessage("editTable.label.sourceType"));
            header.add(I18nUtils.getMessage("editTable.label.comment"));
            fkData.add(header);

            for (ForeignKeyInfo fk : foreignKeyList) {
                List<String> row = new ArrayList<>();
                row.add(fk.getReferencedTable());
                row.add(fk.getReferencedColumnName());
                row.add(fk.getTableName());
                row.add(fk.getColumnName());
                row.add(fk.getSourceType());
                row.add(fk.getComment());
                fkData.add(row);
            }

            com.alibaba.excel.write.metadata.WriteSheet fkSheet = EasyExcel.writerSheet(I18nUtils.getMessage("workspace.tableRelation.title"))
                    .build();
            excelWriter.write(fkData, fkSheet);
        }

        excelWriter.finish();
    }
}
