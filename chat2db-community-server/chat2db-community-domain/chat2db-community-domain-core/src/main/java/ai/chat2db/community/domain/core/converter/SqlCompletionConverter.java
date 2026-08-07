package ai.chat2db.community.domain.core.converter;

import ai.chat2db.community.domain.api.enums.completion.SqlCompletionCandidateTypeEnum;
import ai.chat2db.community.domain.api.enums.completion.SqlCompletionParameterModeTypeEnum;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionCandidate;
import ai.chat2db.spi.ISQLIdentifierProcessor;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.Function;
import ai.chat2db.community.domain.api.model.metadata.FunctionParameter;
import ai.chat2db.community.domain.api.model.metadata.Procedure;
import ai.chat2db.community.domain.api.model.metadata.ProcedureParameter;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.Tablespace;
import ai.chat2db.community.domain.api.model.metadata.Trigger;
import java.util.List;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Context;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public abstract class SqlCompletionConverter {

    public SqlCompletionCandidate database2candidate(Database database,
                                                     String datasourceName,
                                                     @Context ISQLIdentifierProcessor identifierProcessor) {
        if (database == null) {
            return null;
        }
        SqlCompletionCandidate candidate = baseCandidate(SqlCompletionCandidateTypeEnum.DATABASE,
                database.getName(), identifierProcessor);
        candidate.setDatabaseName(database.getName());
        candidate.setDatasourceName(datasourceName);
        candidate.setDescription(datasourceDescription(datasourceName));
        return candidate;
    }

    public List<SqlCompletionCandidate> databases2candidates(List<Database> databases,
                                                             String datasourceName,
                                                             @Context ISQLIdentifierProcessor identifierProcessor) {
        return databases == null ? List.of()
                : databases.stream()
                .map(database -> database2candidate(database, datasourceName, identifierProcessor))
                .toList();
    }

    public SqlCompletionCandidate tablespace2candidate(Tablespace tablespace,
                                                       String datasourceName,
                                                       @Context ISQLIdentifierProcessor identifierProcessor) {
        if (tablespace == null) {
            return null;
        }
        SqlCompletionCandidate candidate = baseCandidate(SqlCompletionCandidateTypeEnum.TABLESPACE,
                tablespace.getName(), identifierProcessor);
        candidate.setObjectName(tablespace.getName());
        candidate.setDatasourceName(datasourceName);
        candidate.setDescription(datasourceDescription(datasourceName));
        return candidate;
    }

    public List<SqlCompletionCandidate> tablespaces2candidates(List<Tablespace> tablespaces,
                                                               String datasourceName,
                                                               @Context ISQLIdentifierProcessor identifierProcessor) {
        return tablespaces == null ? List.of()
                : tablespaces.stream()
                .map(tablespace -> tablespace2candidate(tablespace, datasourceName, identifierProcessor))
                .toList();
    }

    public SqlCompletionCandidate schema2candidate(Schema schema,
                                                   String databaseName,
                                                   String datasourceName,
                                                   @Context ISQLIdentifierProcessor identifierProcessor) {
        if (schema == null) {
            return null;
        }
        SqlCompletionCandidate candidate = baseCandidate(SqlCompletionCandidateTypeEnum.SCHEMA,
                schema.getName(), identifierProcessor);
        candidate.setDatabaseName(firstNonBlank(schema.getDatabaseName(), databaseName));
        candidate.setSchemaName(schema.getName());
        candidate.setDatasourceName(datasourceName);
        candidate.setDetail(wrapDetail(candidate.getDatabaseName()));
        candidate.setDescription(datasourceDescription(datasourceName));
        return candidate;
    }

    public List<SqlCompletionCandidate> schemas2candidates(List<Schema> schemas,
                                                           String databaseName,
                                                           String datasourceName,
                                                           @Context ISQLIdentifierProcessor identifierProcessor) {
        return schemas == null ? List.of()
                : schemas.stream()
                .map(schema -> schema2candidate(schema, databaseName, datasourceName, identifierProcessor))
                .toList();
    }

    public SqlCompletionCandidate table2candidate(Table table,
                                                  String databaseName,
                                                  String schemaName,
                                                  String datasourceName,
                                                  @Context ISQLIdentifierProcessor identifierProcessor) {
        if (table == null) {
            return null;
        }
        SqlCompletionCandidate candidate = baseCandidate(SqlCompletionCandidateTypeEnum.TABLE,
                table.getName(), identifierProcessor);
        candidate.setDatabaseName(firstNonBlank(table.getDatabaseName(), databaseName));
        candidate.setSchemaName(firstNonBlank(table.getSchemaName(), schemaName));
        candidate.setTableName(table.getName());
        candidate.setDatasourceName(datasourceName);
        candidate.setDetail(relationDetail(candidate.getDatabaseName(), candidate.getSchemaName()));
        candidate.setDescription(datasourceDescription(datasourceName));
        candidate.setObjectType(table.getType());
        candidate.setComment(table.getComment());
        return candidate;
    }

    public List<SqlCompletionCandidate> tables2candidates(List<Table> tables,
                                                          String databaseName,
                                                          String schemaName,
                                                          String datasourceName,
                                                          @Context ISQLIdentifierProcessor identifierProcessor) {
        return tables == null ? List.of()
                : tables.stream()
                .map(table -> table2candidate(table, databaseName, schemaName, datasourceName, identifierProcessor))
                .toList();
    }

    public SqlCompletionCandidate view2candidate(Table view,
                                                 String databaseName,
                                                 String schemaName,
                                                 String datasourceName,
                                                 @Context ISQLIdentifierProcessor identifierProcessor) {
        if (view == null) {
            return null;
        }
        SqlCompletionCandidate candidate = baseCandidate(SqlCompletionCandidateTypeEnum.VIEW,
                view.getName(), identifierProcessor);
        candidate.setDatabaseName(firstNonBlank(view.getDatabaseName(), databaseName));
        candidate.setSchemaName(firstNonBlank(view.getSchemaName(), schemaName));
        candidate.setTableName(view.getName());
        candidate.setObjectName(view.getName());
        candidate.setDatasourceName(datasourceName);
        candidate.setDetail(relationDetail(candidate.getDatabaseName(), candidate.getSchemaName()));
        candidate.setDescription(datasourceDescription(datasourceName));
        candidate.setObjectType(view.getType());
        candidate.setComment(view.getComment());
        return candidate;
    }

    public List<SqlCompletionCandidate> views2candidates(List<Table> views,
                                                         String databaseName,
                                                         String schemaName,
                                                         String datasourceName,
                                                         @Context ISQLIdentifierProcessor identifierProcessor) {
        return views == null ? List.of()
                : views.stream()
                .map(view -> view2candidate(view, databaseName, schemaName, datasourceName, identifierProcessor))
                .toList();
    }

    public SqlCompletionCandidate column2candidate(TableColumn column,
                                                   String databaseName,
                                                   String schemaName,
                                                   String tableName,
                                                   String datasourceName,
                                                   @Context ISQLIdentifierProcessor identifierProcessor) {
        if (column == null) {
            return null;
        }
        SqlCompletionCandidate candidate = baseCandidate(SqlCompletionCandidateTypeEnum.COLUMN,
                column.getName(), identifierProcessor);
        candidate.setDatabaseName(firstNonBlank(column.getDatabaseName(), databaseName));
        candidate.setSchemaName(firstNonBlank(column.getSchemaName(), schemaName));
        candidate.setTableName(firstNonBlank(column.getTableName(), tableName));
        candidate.setColumnName(column.getName());
        candidate.setDatasourceName(datasourceName);
        candidate.setDetail(firstNonBlank(column.getColumnType(), datasourceName));
        candidate.setDataType(column.getColumnType());
        candidate.setComment(column.getComment());
        candidate.setSortRank(column.getOrdinalPosition());
        return candidate;
    }

    public List<SqlCompletionCandidate> columns2candidates(List<TableColumn> columns,
                                                           String databaseName,
                                                           String schemaName,
                                                           String tableName,
                                                           String datasourceName,
                                                           @Context ISQLIdentifierProcessor identifierProcessor) {
        return columns == null ? List.of()
                : columns.stream()
                .map(column -> column2candidate(column, databaseName, schemaName, tableName, datasourceName,
                        identifierProcessor))
                .toList();
    }

    public SqlCompletionCandidate function2candidate(Function function,
                                                     String databaseName,
                                                     String schemaName,
                                                     String datasourceName,
                                                     @Context ISQLIdentifierProcessor identifierProcessor) {
        if (function == null) {
            return null;
        }
        SqlCompletionCandidate candidate = baseCandidate(SqlCompletionCandidateTypeEnum.FUNCTION,
                function.getFunctionName(), identifierProcessor);
        candidate.setDatabaseName(firstNonBlank(function.getDatabaseName(), databaseName));
        candidate.setSchemaName(firstNonBlank(function.getSchemaName(), schemaName));
        candidate.setObjectName(function.getFunctionName());
        candidate.setDatasourceName(datasourceName);
        candidate.setDetail(relationDetail(candidate.getDatabaseName(), candidate.getSchemaName()));
        candidate.setDescription(datasourceDescription(datasourceName));
        candidate.setObjectType(String.valueOf(function.getFunctionType()));
        return candidate;
    }

    public List<SqlCompletionCandidate> functions2candidates(List<Function> functions,
                                                             String databaseName,
                                                             String schemaName,
                                                             String datasourceName,
                                                             @Context ISQLIdentifierProcessor identifierProcessor) {
        return functions == null ? List.of()
                : functions.stream()
                .map(function -> function2candidate(function, databaseName, schemaName, datasourceName,
                        identifierProcessor))
                .toList();
    }

    public SqlCompletionCandidate procedure2candidate(Procedure procedure,
                                                      String databaseName,
                                                      String schemaName,
                                                      String datasourceName,
                                                      @Context ISQLIdentifierProcessor identifierProcessor) {
        if (procedure == null) {
            return null;
        }
        SqlCompletionCandidate candidate = baseCandidate(SqlCompletionCandidateTypeEnum.PROCEDURE,
                procedure.getProcedureName(), identifierProcessor);
        candidate.setDatabaseName(firstNonBlank(procedure.getDatabaseName(), databaseName));
        candidate.setSchemaName(firstNonBlank(procedure.getSchemaName(), schemaName));
        candidate.setObjectName(procedure.getProcedureName());
        candidate.setDatasourceName(datasourceName);
        candidate.setDetail(relationDetail(candidate.getDatabaseName(), candidate.getSchemaName()));
        candidate.setDescription(datasourceDescription(datasourceName));
        candidate.setObjectType(String.valueOf(procedure.getProcedureType()));
        return candidate;
    }

    public List<SqlCompletionCandidate> procedures2candidates(List<Procedure> procedures,
                                                              String databaseName,
                                                              String schemaName,
                                                              String datasourceName,
                                                              @Context ISQLIdentifierProcessor identifierProcessor) {
        return procedures == null ? List.of()
                : procedures.stream()
                .map(procedure -> procedure2candidate(procedure, databaseName, schemaName, datasourceName,
                        identifierProcessor))
                .toList();
    }

    public SqlCompletionCandidate functionParameter2candidate(FunctionParameter parameter) {
        if (parameter == null || StringUtils.isBlank(parameter.getColumnName())) {
            return null;
        }
        SqlCompletionCandidate candidate = SqlCompletionCandidate.of(SqlCompletionCandidateTypeEnum.PARAMETER,
                parameter.getColumnName());
        candidate.setInsertText(parameter.getColumnName());
        candidate.setColumnName(parameter.getColumnName());
        candidate.setDataType(parameter.getTypeName());
        candidate.setDetail(parameter.getTypeName());
        candidate.setParameterMode(SqlCompletionParameterModeTypeEnum.fromFunctionColumnType(parameter.getColumnType()));
        candidate.setSortRank(parameter.getOrdinalPosition());
        return candidate;
    }

    public List<SqlCompletionCandidate> functionParameters2candidates(List<FunctionParameter> parameters) {
        return parameters == null ? List.of()
                : parameters.stream()
                .map(this::functionParameter2candidate)
                .toList();
    }

    public SqlCompletionCandidate procedureParameter2candidate(ProcedureParameter parameter) {
        if (parameter == null || StringUtils.isBlank(parameter.getColumnName())) {
            return null;
        }
        SqlCompletionCandidate candidate = SqlCompletionCandidate.of(SqlCompletionCandidateTypeEnum.PARAMETER,
                parameter.getColumnName());
        candidate.setInsertText(parameter.getColumnName());
        candidate.setColumnName(parameter.getColumnName());
        candidate.setDataType(parameter.getTypeName());
        candidate.setDetail(parameter.getTypeName());
        candidate.setParameterMode(SqlCompletionParameterModeTypeEnum.fromProcedureColumnType(parameter.getColumnType()));
        candidate.setSortRank(parameter.getOrdinalPosition());
        return candidate;
    }

    public List<SqlCompletionCandidate> procedureParameters2candidates(List<ProcedureParameter> parameters) {
        return parameters == null ? List.of()
                : parameters.stream()
                .map(this::procedureParameter2candidate)
                .toList();
    }

    public SqlCompletionCandidate trigger2candidate(Trigger trigger,
                                                    String databaseName,
                                                    String schemaName,
                                                    String datasourceName,
                                                    @Context ISQLIdentifierProcessor identifierProcessor) {
        if (trigger == null) {
            return null;
        }
        SqlCompletionCandidate candidate = baseCandidate(SqlCompletionCandidateTypeEnum.TRIGGER,
                trigger.getTriggerName(), identifierProcessor);
        candidate.setDatabaseName(firstNonBlank(trigger.getDatabaseName(), databaseName));
        candidate.setSchemaName(firstNonBlank(trigger.getSchemaName(), schemaName));
        candidate.setObjectName(trigger.getTriggerName());
        candidate.setDatasourceName(datasourceName);
        candidate.setDetail(relationDetail(candidate.getDatabaseName(), candidate.getSchemaName()));
        candidate.setDescription(datasourceDescription(datasourceName));
        candidate.setObjectType(trigger.getEventManipulation());
        return candidate;
    }

    public List<SqlCompletionCandidate> triggers2candidates(List<Trigger> triggers,
                                                            String databaseName,
                                                            String schemaName,
                                                            String datasourceName,
                                                            @Context ISQLIdentifierProcessor identifierProcessor) {
        return triggers == null ? List.of()
                : triggers.stream()
                .map(trigger -> trigger2candidate(trigger, databaseName, schemaName, datasourceName,
                        identifierProcessor))
                .toList();
    }

    private SqlCompletionCandidate baseCandidate(SqlCompletionCandidateTypeEnum type,
                                                 String label,
                                                 ISQLIdentifierProcessor identifierProcessor) {
        SqlCompletionCandidate candidate = SqlCompletionCandidate.of(type, label);
        candidate.setInsertText(quote(label, identifierProcessor));
        return candidate;
    }

    private String quote(String name, ISQLIdentifierProcessor identifierProcessor) {
        if (StringUtils.isBlank(name) || identifierProcessor == null) {
            return name;
        }
        return identifierProcessor.quoteIdentifier(name);
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.isNotBlank(first) ? first : second;
    }

    private String relationDetail(String databaseName, String schemaName) {
        String value = StringUtils.join(
                Stream.of(databaseName, schemaName).filter(StringUtils::isNotBlank).toList(), ".");
        return wrapDetail(value);
    }

    private String wrapDetail(String value) {
        return StringUtils.isBlank(value) ? null : " (" + value + ")";
    }

    private String datasourceDescription(String datasourceName) {
        return StringUtils.isBlank(datasourceName) ? null : "@" + StringUtils.removeStart(datasourceName, "@");
    }
}
