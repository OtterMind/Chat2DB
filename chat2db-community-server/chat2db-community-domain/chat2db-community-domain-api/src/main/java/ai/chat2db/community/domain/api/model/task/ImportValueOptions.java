package ai.chat2db.community.domain.api.model.task;

import lombok.Data;
import ai.chat2db.community.tools.exception.BusinessException;

/** Date and number options for text cells. Native file values retain their types. */
@Data
public class ImportValueOptions implements ImportValueFormat {
    private String dateOrder = "YMD";
    private String dateTimeOrder = "DATE_TIME";
    private String dateDelimiter = "-";
    private String yearDelimiter = "-";
    private String timeDelimiter = ":";
    private String decimalSymbol = ".";

    protected void validateFormats() {
        // Keep the accepted format combinations identical to CSV imports.
        CsvOptions normalized;
        try {
            normalized = CsvOptions.builder().dateOrder(dateOrder).dateTimeOrder(dateTimeOrder)
                .dateDelimiter(dateDelimiter).yearDelimiter(yearDelimiter)
                .timeDelimiter(timeDelimiter).decimalSymbol(decimalSymbol).build().validate();
        } catch (BusinessException e) {
            throw new BusinessException("import.preview.invalidValueFormat", null, e);
        }
        dateOrder = normalized.getDateOrder();
        dateTimeOrder = normalized.getDateTimeOrder();
        dateDelimiter = normalized.getDateDelimiter();
        yearDelimiter = normalized.getYearDelimiter();
        timeDelimiter = normalized.getTimeDelimiter();
        decimalSymbol = normalized.getDecimalSymbol();
    }
}
