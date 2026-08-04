package ai.chat2db.community.domain.api.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Regression test for code-review finding core:domain-api-4:
 * the misspelled XLXS(".xlxs") constant must be XLSX(".xlsx").
 */
class ExportFileSuffixEnumTest {

    @Test
    void xlsxConstantExistsWithCorrectSuffix() {
        assertEquals(".xlsx", ExportFileSuffixEnum.XLSX.getSuffix());
    }

    @Test
    void misspelledConstantRemoved() {
        for (ExportFileSuffixEnum value : ExportFileSuffixEnum.values()) {
            if (".xlxs".equals(value.getSuffix()) || "XLXS".equals(value.name())) {
                throw new AssertionError("misspelled XLXS/.xlxs constant still present");
            }
        }
        assertNotNull(ExportFileSuffixEnum.valueOf("XLSX"));
    }
}
