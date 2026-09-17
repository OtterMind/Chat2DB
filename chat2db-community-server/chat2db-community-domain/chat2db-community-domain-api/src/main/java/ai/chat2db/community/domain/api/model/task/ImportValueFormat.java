package ai.chat2db.community.domain.api.model.task;

/** Text value formats shared by tabular importers. */
public interface ImportValueFormat {
    String getDateOrder();
    String getDateTimeOrder();
    String getDateDelimiter();
    String getYearDelimiter();
    String getTimeDelimiter();
    String getDecimalSymbol();
}
