package ai.chat2db.community.domain.core.impl.db;

/** Shared value representation used by import preview parsers. */
final class ExcelParser {

    private ExcelParser() {
    }

    record CellValue(String value, String type) {
    }
}
