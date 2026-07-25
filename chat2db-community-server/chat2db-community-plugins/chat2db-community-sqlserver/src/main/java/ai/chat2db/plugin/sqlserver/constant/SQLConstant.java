package ai.chat2db.plugin.sqlserver.constant;

import ai.chat2db.community.tools.util.EasyStringUtils;


public class SQLConstant {
    public static final String TABLE_COMMENT_TEMPLATE = "exec sp_addextendedproperty 'MS_Description',N'%s','SCHEMA',N'%s','TABLE',N'%s' \ngo\n";
    public static final String INDEX_COMMENT_TEMPLATE = "exec sp_addextendedproperty 'MS_Description',N'%s','SCHEMA',N'%s','TABLE',N'%s','INDEX',N'%s' \ngo\n";
    public static final String COLUMN_COMMENT_TEMPLATE = "exec sp_addextendedproperty 'MS_Description',N'%s','SCHEMA',N'%s','TABLE',N'%s','COLUMN',N'%s' \ngo\n";
    public static final String CONSTRAINT_COMMENT_TEMPLATE = "exec sp_addextendedproperty 'MS_Description',N'%s','SCHEMA',N'%s','TABLE',N'%s','CONSTRAINT',N'%s' \ngo\n";

    public static String buildTableComment(String tableComment, String schemaName, String tableName) {
        return String.format(TABLE_COMMENT_TEMPLATE, EasyStringUtils.escapeString(tableComment), schemaName, tableName);
    }

    public static String buildIndexComment(String indexComment, String schemaName, String tableName, String indexName) {
        return String.format(INDEX_COMMENT_TEMPLATE, EasyStringUtils.escapeString(indexComment), schemaName, tableName, indexName);
    }

    public static String buildColumnComment(String columnComment, String schemaName, String tableName, String columnName) {
        return String.format(COLUMN_COMMENT_TEMPLATE, EasyStringUtils.escapeString(columnComment), schemaName, tableName, columnName);
    }

    public static String buildConstraintComment(String constraintComment, String schemaName, String tableName, String constraintName) {
        return String.format(CONSTRAINT_COMMENT_TEMPLATE, EasyStringUtils.escapeString(constraintComment), schemaName, tableName, constraintName);
    }
    public static final String VIEWS_DDL_SQL = "SELECT TABLE_NAME, VIEW_DEFINITION FROM INFORMATION_SCHEMA.VIEWS " +
            "WHERE TABLE_SCHEMA = ? AND TABLE_CATALOG = ?; ";
    public static final String ROUTINES_SQL = "SELECT name FROM sys.objects WHERE type = ? and SCHEMA_ID = SCHEMA_ID(?) order by name;";

    public static final String ROUTINES_DDL_SQL
            = "SELECT type_desc, OBJECT_NAME(object_id) AS FunctionName, OBJECT_DEFINITION(object_id) AS "
            + "definition FROM sys.objects WHERE type_desc IN(%s) and name = '%s' ;";

    public static final String DROP_FUNCTION_SQL = """
                                                   IF EXISTS (SELECT * FROM sys.all_objects WHERE object_id = OBJECT_ID(N'[%s]') AND type IN ('FN', 'FS', 'FT', 'IF', 'TF'))
                                                   DROP FUNCTION [%s]
                                                   GO
                                                   """;

    public static final String DROP_PROCEDURE_SQL = """
                                                    IF EXISTS (SELECT * FROM sys.all_objects WHERE object_id = OBJECT_ID(N'[%s]') AND type IN ('P', 'PC', 'RF', 'X'))
                                                    DROP PROCEDURE [%s]
                                                    GO
                                                    """;

    public static final String TRIGGERS_SQL = """
                                                  SELECT
                                                      s.name AS schemaName,
                                                      o.name AS tableName,
                                                      t.name AS triggerName,
                                                      OBJECT_DEFINITION(t.object_id) AS triggerDefinition,
                                                      CASE
                                                          WHEN t.is_disabled = 0 THEN 'Enabled'
                                                          ELSE 'Disabled'
                                                      END AS status
                                                  FROM
                                                      sys.triggers t
                                                  JOIN
                                                      sys.objects o ON t.parent_id = o.object_id
                                                  JOIN
                                                      sys.schemas s ON o.schema_id = s.schema_id
                                                  WHERE
                                                      s.name = ?
                                                  ORDER BY
                                                      t.name;
                                                  """;


    public static final String TRIGGER_DDL_SQL = """
                                             SELECT
                                                      s.name AS schemaName,
                                                      o.name AS tableName,
                                                      t.name AS triggerName,
                                                      OBJECT_DEFINITION(t.object_id) AS triggerDefinition,
                                                      CASE
                                                          WHEN t.is_disabled = 0 THEN 'Enabled'
                                                          ELSE 'Disabled'
                                                      END AS status
                                                  FROM
                                                      sys.triggers t
                                                  JOIN
                                                      sys.objects o ON t.parent_id = o.object_id
                                                  JOIN
                                                      sys.schemas s ON o.schema_id = s.schema_id
                                                  WHERE
                                                      s.name = ? and t.name=?
                                                  ORDER BY
                                                      t.name;
                                           """;


    public static final String PK_UQ_CONSTRAINT_SQL = """
                                                      SELECT kc.name              AS CONSTRAINT_NAME,
                                                             c.name               AS COLUMN_NAME,
                                                             ic.is_descending_key AS IS_DESC,
                                                             kc.type              AS CONSTRAINT_TYPE,
                                                             i.type_desc          AS INDEX_TYPE
                                                      FROM sys.key_constraints kc
                                                               INNER JOIN sys.index_columns ic ON kc.parent_object_id = ic.object_id AND kc.unique_index_id = ic.index_id
                                                               INNER JOIN sys.columns c ON ic.object_id = c.object_id AND ic.column_id = c.column_id
                                                               INNER JOIN sys.indexes i ON ic.object_id = i.object_id AND ic.index_id = i.index_id
                                                      WHERE kc.type IN ('PK', 'UQ')
                                                        AND kc.parent_object_id = OBJECT_ID('%s.%s');""";

    public static final String TABLE_COMMENT_SQL = """
                                                   SELECT
                                                       t.name AS TABLE_NAME,
                                                       p.value AS TABLE_COMMENT
                                                   FROM
                                                       sys.tables t
                                                   JOIN
                                                       sys.extended_properties p ON t.object_id = p.major_id
                                                   WHERE
                                                       p.minor_id = 0 AND p.class = 1 AND p.name = 'MS_Description'
                                                       AND SCHEMA_NAME(t.schema_id) = ?
                                                       AND t.name = ?;""";
    public static final String SELECT_CONSTRAINT_COMMENT_SQL = """
                                                               SELECT ep.value AS 'CONSTRAINT_COMMENT',
                                                                      o.name   AS  'CONSTRAINT_NAME'
                                                               FROM sys.extended_properties AS ep
                                                                        JOIN
                                                                    sys.objects AS o ON ep.major_id = o.object_id
                                                                        JOIN
                                                                    sys.tables AS t ON o.parent_object_id = t.object_id
                                                                        JOIN
                                                                    sys.schemas AS s ON t.schema_id = s.schema_id
                                                               WHERE o.type in ('C', 'F', 'PK', 'UQ')
                                                                 AND s.name = ?
                                                                 AND t.name = ?
                                                                 AND ep.name = N'MS_Description';""";
    public static final String CHECK_CONSTRAINT_SQL = """
                                                      select
                                                      name       as CONSTRAINT_NAME,
                                                      definition as CONSTRAINT_DEFINITION
                                                      from sys.check_constraints
                                                      where parent_object_id = object_id('%s.%s');""";
    public static final String FOREIGN_KEY_SQL = """
                                                 SELECT
                                                     fk.name AS CONSTRAINT_NAME,
                                                     c.name AS COLUMN_NAME,
                                                     SCHEMA_NAME(ro.schema_id) AS REFERENCED_SCHEMA_NAME,
                                                     OBJECT_NAME(fk.referenced_object_id) AS REFERENCED_TABLE_NAME,
                                                     rc.name AS REFERENCED_COLUMN_NAME,
                                                     fk.delete_referential_action                                           as DELETE_ACTION,
                                                     fk.update_referential_action                                           as UPDATE_ACTION
                                                 FROM
                                                     sys.foreign_keys AS fk
                                                 INNER JOIN
                                                     sys.objects o ON fk.parent_object_id = o.object_id
                                                 INNER JOIN
                                                     sys.objects ro ON fk.referenced_object_id = ro.object_id
                                                 INNER JOIN
                                                     sys.foreign_key_columns AS fkc ON fk.object_id = fkc.constraint_object_id
                                                 INNER JOIN
                                                     sys.columns AS c ON fkc.parent_column_id = c.column_id AND fkc.parent_object_id = c.object_id
                                                 INNER JOIN
                                                     sys.columns AS rc ON fkc.referenced_column_id = rc.column_id AND fkc.referenced_object_id = rc.object_id
                                                 WHERE
                                                     SCHEMA_NAME(o.schema_id) = ?
                                                     and OBJECT_NAME(fk.parent_object_id) = ?;""";
    public static final String PARTITION_DEF_SQL = """
                                                   SELECT
                                                       c.name AS PARTITION_COLUMN_NAME,
                                                       ps.name AS PARTITION_SCHEME_NAME
                                                   FROM
                                                       sys.tables AS t
                                                       JOIN sys.indexes AS i ON t.object_id = i.object_id
                                                       JOIN sys.index_columns AS ic ON i.object_id = ic.object_id AND i.index_id = ic.index_id
                                                       JOIN sys.columns AS c ON ic.object_id = c.object_id AND ic.column_id = c.column_id
                                                       JOIN sys.partition_schemes AS ps ON i.data_space_id = ps.data_space_id
                                                       JOIN sys.partition_functions AS pf ON ps.function_id = pf.function_id
                                                   WHERE
                                                       t.schema_id = SCHEMA_ID(?) AND
                                                        t.name = ? AND
                                                       ic.partition_ordinal > 0  -- partition_ordinal > 0 identifies a partition key""";
    public static final String SELECT_TABLE_COLUMNS = """
                                                      SELECT c.name            as COLUMN_NAME,
                                                              c.is_sparse      as IS_SPARSE,
                                                              c.is_nullable    as IS_NULLABLE,
                                                              c.column_id      as ORDINAL_POSITION,
                                                              c.max_length     as COLUMN_SIZE,
                                                              c.precision      as COLUMN_PRECISION,
                                                              c.scale          as NUMERIC_SCALE,
                                                              c.collation_name as COLLATION_NAME,
                                                              ty.name          as DATA_TYPE,
                                                              t.name,
                                                              def.definition   as COLUMN_DEFAULT,
                                                              ep.value         as COLUMN_COMMENT,
                                                              ident.seed_value      as SEED_VALUE,
                                                              ident.increment_value as INCREMENT_VALUE,
                                                              cc.definition    as COMPUTED_DEFINITION,
                                                              c.is_identity         as IS_IDENTITY,
                                                              cc.is_persisted  as IS_PERSISTED
                                                       from sys.columns c
                                                                LEFT JOIN sys.tables t on c.object_id = t.object_id
                                                                LEFT JOIN sys.types ty ON c.user_type_id = ty.user_type_id
                                                                LEFT JOIN sys.default_constraints def ON c.default_object_id = def.object_id
                                                                LEFT JOIN sys.extended_properties ep
                                                                          ON t.object_id = ep.major_id AND c.column_id = ep.minor_id and class_desc != 'INDEX'
                                                                LEFT JOIN sys.computed_columns cc on cc.object_id = c.object_id and cc.column_id = c.column_id
                                                                LEFT JOIN sys.identity_columns ident ON c.object_id = ident.object_id AND c.column_id = ident.column_id
                                                       WHERE t.schema_id = SCHEMA_ID(?) and t.name = ?   ;""";
    public static final String INDEX_SQL = """
                                           SELECT ic.key_ordinal       AS COLUMN_POSITION,
                                                  ic.is_descending_key as DESCEND,
                                                  ind.name             AS INDEX_NAME,
                                                  ind.is_unique        AS IS_UNIQUE,
                                                  col.name             AS COLUMN_NAME,
                                                  ind.type_desc        AS INDEX_TYPE,
                                                  ind.is_primary_key   AS IS_PRIMARY,
                                                  ep.value             AS INDEX_COMMENT,
                                                  ind.is_unique_constraint AS IS_UNIQUE_CONSTRAINT
                                           FROM sys.indexes ind
                                                    INNER JOIN sys.index_columns ic
                                                               ON ind.object_id = ic.object_id and ind.index_id = ic.index_id and ic.key_ordinal > 0
                                                    INNER JOIN sys.columns col ON ic.object_id = col.object_id and ic.column_id = col.column_id
                                                    INNER JOIN sys.tables t ON ind.object_id = t.object_id
                                                    LEFT JOIN sys.key_constraints kc ON ind.object_id = kc.parent_object_id AND ind.index_id = kc.unique_index_id
                                                    LEFT JOIN sys.extended_properties ep ON ind.object_id = ep.major_id AND ind.index_id = ep.minor_id and ep.class_desc !='OBJECT_OR_COLUMN'
                                           WHERE t.schema_id = SCHEMA_ID(?)
                                             and t.name = ?
                                           ORDER BY t.name, ind.name, ind.index_id, ic.index_column_id""";
    public static final String ORDER_TABLES_SQL = """
                                                  SELECT DISTINCT t.name AS TABLE_NAME
                                                  FROM sys.tables t
                                                           INNER JOIN sys.schemas s ON t.schema_id = s.schema_id
                                                           LEFT JOIN sys.foreign_keys fk ON t.object_id = fk.parent_object_id
                                                  WHERE fk.parent_object_id IS NULL
                                                    AND t.object_id NOT IN (SELECT ps.object_id FROM sys.partitions ps WHERE ps.partition_number > 1)
                                                    AND s.name = ?
                                                  union all
                                                  SELECT DISTINCT t.name AS TableName
                                                  FROM sys.tables t
                                                           INNER JOIN sys.schemas s ON t.schema_id = s.schema_id
                                                           INNER JOIN sys.partitions ps ON t.object_id = ps.object_id
                                                  WHERE ps.partition_number > 1
                                                    AND s.name = ?
                                                  union all
                                                  SELECT DISTINCT t.name AS TableName
                                                  FROM sys.tables t
                                                           INNER JOIN sys.schemas s ON t.schema_id = s.schema_id
                                                           INNER JOIN sys.foreign_keys fk ON t.object_id = fk.parent_object_id
                                                  WHERE s.name = ?""";
}
