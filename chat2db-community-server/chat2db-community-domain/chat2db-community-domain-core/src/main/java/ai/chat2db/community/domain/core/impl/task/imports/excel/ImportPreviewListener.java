package ai.chat2db.community.domain.core.impl.task.imports.excel;

import ai.chat2db.community.domain.core.impl.task.imports.ImportFileProbe;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.alibaba.excel.exception.ExcelAnalysisStopException;
import com.alibaba.excel.metadata.data.ReadCellData;
import com.alibaba.excel.util.ConverterUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Collects the header and a bounded number of data rows for an import preview, then stops the
 * EasyExcel read so previewing never scans a whole large workbook.
 */
public final class ImportPreviewListener extends AnalysisEventListener<Map<Integer, String>> {

    private final List<List<String>> rows = new ArrayList<>();

    @Override
    public void invokeHead(Map<Integer, ReadCellData<?>> headCells, AnalysisContext context) {
        Map<Integer, String> headMap = ConverterUtils.convertToStringMap(headCells, context);
        int width = headMap.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
        List<String> header = new ArrayList<>(width);
        for (int index = 0; index < width; index++) {
            header.add(headMap.getOrDefault(index, ""));
        }
        rows.add(header);
    }

    @Override
    public void invoke(Map<Integer, String> data, AnalysisContext context) {
        if (rows.size() > ImportFileProbe.sampleRows()) {
            throw new ExcelAnalysisStopException();
        }
        int width = rows.get(0).size();
        List<String> row = new ArrayList<>(width);
        for (int index = 0; index < width; index++) {
            row.add(data.get(index));
        }
        rows.add(row);
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        // The collected rows are read out by the caller.
    }

    public List<List<String>> rows() {
        return rows;
    }
}
