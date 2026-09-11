package ai.chat2db.community.domain.core.impl.db;

import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ImportPreviewListener extends AnalysisEventListener<Map<Integer, String>> {

    private final int limit;

    private final List<Map<Integer, String>> rows = new ArrayList<>();

    ImportPreviewListener(int limit) {
        this.limit = limit;
    }

    @Override
    public void invokeHeadMap(Map<Integer, String> headMap, AnalysisContext context) {
        rows.add(normalize(headMap));
    }

    @Override
    public void invoke(Map<Integer, String> data, AnalysisContext context) {
        rows.add(normalize(data));
    }

    @Override
    public boolean hasNext(AnalysisContext context) {
        return rows.size() <= limit;
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        // EasyExcel owns the input stream.
    }

    List<Map<Integer, String>> rows() {
        return rows;
    }

    private static Map<Integer, String> normalize(Map<Integer, String> values) {
        Map<Integer, String> normalized = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((index, value) -> normalized.put(index, value == null ? "" : value));
        }
        return normalized;
    }
}
