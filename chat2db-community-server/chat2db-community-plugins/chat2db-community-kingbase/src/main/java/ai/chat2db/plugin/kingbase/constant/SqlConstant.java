package ai.chat2db.plugin.kingbase.constant;


public class SqlConstant {

    public static final String SELECT_TABLE_INDEX = "SELECT tmp.INDISPRIMARY AS Index_primary, tmp.TABLE_SCHEM, tmp.TABLE_NAME, tmp.NON_UNIQUE, tmp.INDEX_QUALIFIER, tmp.INDEX_NAME AS Key_name, tmp.indisclustered, tmp.ORDINAL_POSITION AS Seq_in_index, TRIM ( BOTH '\"' FROM pg_get_indexdef ( tmp.CI_OID, tmp.ORDINAL_POSITION, FALSE ) ) AS Column_name,CASE  tmp.AM_NAME   WHEN 'btree' THEN CASE   tmp.I_INDOPTION [ tmp.ORDINAL_POSITION - 1 ] & 1 :: SMALLINT   WHEN 1 THEN  'D' ELSE'A'  END ELSE NULL  END AS Collation, tmp.CARDINALITY, tmp.PAGES, tmp.FILTER_CONDITION , tmp.AM_NAME AS Index_method, tmp.DESCRIPTION AS Index_comment FROM ( SELECT  n.nspname AS TABLE_SCHEM,  ct.relname AS TABLE_NAME,  NOT i.indisunique AS NON_UNIQUE, NULL AS INDEX_QUALIFIER,  ci.relname AS INDEX_NAME,i.INDISPRIMARY , i.indisclustered ,  ( information_schema._pg_expandarray ( i.indkey ) ).n AS ORDINAL_POSITION,  ci.reltuples AS CARDINALITY,   ci.relpages AS PAGES,  pg_get_expr ( i.indpred, i.indrelid ) AS FILTER_CONDITION,  ci.OID AS CI_OID, i.indoption AS I_INDOPTION,  am.amname AS AM_NAME , d.description  FROM   pg_class ct   JOIN pg_namespace n ON ( ct.relnamespace = n.OID )   JOIN pg_index i ON ( ct.OID = i.indrelid )   JOIN pg_class ci ON ( ci.OID = i.indexrelid )  JOIN pg_am am ON ( ci.relam = am.OID )      left outer join pg_description d on i.indexrelid = d.objoid  WHERE  n.nspname = '%s'   AND ct.relname = '%s'   ) AS tmp ;";
    public static String FUNCTION_LIST_SQL = """
                                             SELECT
                                               p.proname,
                                               n.nspname,
                                               p.prokind
                                             FROM
                                               sys_catalog.sys_proc p
                                               JOIN sys_catalog.sys_namespace n ON p.pronamespace = n.oid
                                             WHERE
                                               p.prokind IN ('i', 'f')
                                               AND lower(n.nspname) = lower(?)
                                               AND NOT EXISTS (
                                                 SELECT
                                                   1
                                                 FROM
                                                   sys_catalog.sys_depend d
                                                   JOIN sys_catalog.sys_extension e ON e.oid = d.refobjid
                                                 WHERE
                                                   d.objid = p.oid
                                                   AND d.deptype = 'e'
                                               )
                                             ORDER BY
                                               p.proname
                                             """;
    public static String FUNCTION_SQL = """
                                        SELECT
                                          p.proname,
                                          p.prokind,
                                          sys_catalog.sys_get_functiondef(p.oid) as "code"
                                        FROM
                                          sys_catalog.sys_proc p
                                          JOIN sys_catalog.sys_namespace n ON p.pronamespace = n.oid
                                        WHERE
                                          p.prokind IN ('i', 'f')
                                          AND lower(n.nspname) = lower(?)
                                          AND lower(p.proname) = lower(?)
                                        """;
    public static String PROCEDURE_SQL = """
                                         SELECT
                                           p.proname,
                                           p.prokind,
                                           sys_catalog.sys_get_functiondef(p.oid) as "code"
                                         FROM
                                           sys_catalog.sys_proc p
                                           JOIN sys_catalog.sys_namespace n ON p.pronamespace = n.oid
                                         WHERE
                                           p.prokind = 'p'
                                           AND lower(n.nspname) = lower(?)
                                           AND lower(p.proname) = lower(?)
                                         """;
    public static String TRIGGER_SQL
            = "SELECT n.nspname AS \"schema\", c.relname AS \"table_name\", t.tgname AS \"trigger_name\", t.tgenabled AS "
            + "\"enabled\", pg_get_triggerdef(t.oid) AS \"trigger_body\" FROM pg_trigger t JOIN pg_class c ON c.oid = t"
            + ".tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = '%s' AND t.tgname ='%s';";
    public static String TRIGGER_SQL_LIST
            = "SELECT n.nspname AS \"schema\", c.relname AS \"table_name\", t.tgname AS \"trigger_name\", t.tgenabled AS "
            + "\"enabled\", pg_get_triggerdef(t.oid) AS \"trigger_body\" FROM pg_trigger t JOIN pg_class c ON c.oid = t"
            + ".tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = '%s';";
    public static String VIEW_SQL
            = "SELECT schemaname, viewname, definition FROM pg_views WHERE schemaname = '%s' AND viewname = '%s';";


    public static final String ENUM_TYPE_DDL_SQL = """
                                                   SELECT 'CREATE TYPE "' || t.typname || '" AS ENUM (' ||
                                                       string_agg(quote_literal(e.enumlabel), ', ') || ');' AS ddl,
                                                       t.typname as type_name
                                                   FROM pg_type t
                                                   JOIN pg_enum e ON t.oid = e.enumtypid
                                                   JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
                                                   WHERE t.typtype = 'e'
                                                   AND n.nspname = ?  -- Restrict the query to the requested schema
                                                   GROUP BY n.nspname, t.typname;""";
    public static final String SEQUENCES_SQL = """
                                                   SELECT
                                                     n.nspname AS SEQUENCE_SCHEMA,
                                                     c.relname AS SEQUENCE_NAME,
                                                     s.seqstart AS START_VALUE,
                                                     s.seqincrement AS INCREMENT_BY,
                                                     s.seqmin AS MIN_VALUE,
                                                     s.seqmax AS MAX_VALUE,
                                                     s.seqcache AS CACHE_SIZE,
                                                     s.seqcycle AS IS_CYCLED
                                                   FROM
                                                     pg_class c
                                                     JOIN pg_namespace n ON n.oid = c.relnamespace
                                                     JOIN pg_sequence s ON s.seqrelid = c.oid
                                                   WHERE
                                                     c.relkind = 'S'
                                                     AND n.nspname = ?
                                               """;
    public static final String TABLE_SPACE_SQL = """
                                                 select tablespace
                                                 from pg_tables
                                                 where schemaname = ?
                                                   and tablename = ?
                                                   and tablespace is not null;""";
    public static final String PARTITIONED_CONDITION_SQL = """
                                                           SELECT pg_get_partkeydef(c1.oid) as partition_key
                                                           FROM pg_class c1
                                                                    JOIN pg_namespace n ON (n.oid = c1.relnamespace)
                                                                    LEFT JOIN pg_partitioned_table p ON (c1.oid = p.partrelid)
                                                           WHERE n.nspname = ?
                                                             and n.oid = c1.relnamespace
                                                             and c1.relname = ?
                                                             and c1.relkind = 'p'
                                                             and pg_get_partkeydef(c1.oid) IS NOT NULL
                                                             and pg_get_partkeydef(c1.oid) <> '';""";
    public static final String CONSTRAINT_SQL = """
                                                SELECT con.conname                   as CONSTRAINT_NAME,
                                                       con.contype                   as CONSTRAINT_TYPE,
                                                       CASE
                                                           WHEN con.contype = 'p' THEN 1 -- primary key constraint
                                                           WHEN con.contype = 'u' THEN 2 -- unique constraint
                                                           WHEN con.contype = 'f' THEN 3 -- foreign key constraint
                                                           WHEN con.contype = 'c' THEN 4
                                                           ELSE 5
                                                           END                       as type_rank,
                                                       pg_get_constraintdef(con.oid) as CONSTRAINT_DEFINITION
                                                FROM pg_catalog.pg_constraint con
                                                         JOIN pg_catalog.pg_class rel ON rel.oid = con.conrelid
                                                         JOIN pg_catalog.pg_namespace nsp ON nsp.oid = connamespace
                                                WHERE nsp.nspname = ?
                                                  AND rel.relname = ?
                                                  AND con.conparentid = 0
                                                ORDER BY type_rank;""";
    public static final String CONSTRAINT_SQL_VERSION_UNDER_ELEVEN = """
                                                                     SELECT con.conname                   as CONSTRAINT_NAME,
                                                                            con.contype                   as CONSTRAINT_TYPE,
                                                                            CASE
                                                                                WHEN con.contype = 'p' THEN 1 -- primary key constraint
                                                                                WHEN con.contype = 'u' THEN 2 -- unique constraint
                                                                                WHEN con.contype = 'f' THEN 3 -- foreign key constraint
                                                                                WHEN con.contype = 'c' THEN 4
                                                                                ELSE 5
                                                                                END                       as type_rank,
                                                                            pg_get_constraintdef(con.oid) as CONSTRAINT_DEFINITION
                                                                     FROM pg_catalog.pg_constraint con
                                                                              JOIN pg_catalog.pg_class rel ON rel.oid = con.conrelid
                                                                              JOIN pg_catalog.pg_namespace nsp ON nsp.oid = connamespace
                                                                     WHERE nsp.nspname = ?
                                                                       AND rel.relname = ?
                                                                     ORDER BY type_rank;""";
    public static final String PARTITIONED_SUB_TABLE_SQL = """
                                                           SELECT
                                                               nsp_child.nspname AS child_schema,
                                                               c.relname AS child_table,
                                                               nsp_parent.nspname AS parent_schema,
                                                               p.relname AS parent_table,
                                                               pg_get_expr(c.relpartbound, c.oid, true) AS partition_definition,  -- Read the partition definition from relpartbound
                                                               EXISTS (
                                                                   SELECT 1
                                                                   FROM pg_inherits AS sub_i
                                                                   WHERE sub_i.inhparent = c.oid
                                                               ) AS is_parent_table  -- Check whether this table is a parent table
                                                           FROM
                                                               pg_class AS c
                                                           JOIN
                                                               pg_namespace AS nsp_child ON c.relnamespace = nsp_child.oid
                                                           JOIN
                                                               pg_inherits AS i ON c.oid = i.inhrelid
                                                           JOIN
                                                               pg_class AS p ON i.inhparent = p.oid
                                                           JOIN
                                                               pg_namespace AS nsp_parent ON p.relnamespace = nsp_parent.oid
                                                           WHERE
                                                               nsp_child.nspname = ?
                                                               AND c.relname = ?;
                                                           """;
    public static final String LIST_PARTITIONED_SUB_TABLE_SQL = """
                                                                WITH PartitionTables AS (
                                                                    SELECT
                                                                        child_ns.nspname AS child_schema,
                                                                        child.relname AS child_table
                                                                    FROM
                                                                        pg_inherits
                                                                    JOIN
                                                                        pg_class parent ON pg_inherits.inhparent = parent.oid
                                                                    JOIN
                                                                        pg_namespace ns ON parent.relnamespace = ns.oid
                                                                    JOIN
                                                                        pg_class child ON pg_inherits.inhrelid = child.oid
                                                                    JOIN
                                                                        pg_namespace child_ns ON child.relnamespace = child_ns.oid
                                                                    WHERE
                                                                        ns.nspname = ?
                                                                        AND parent.relname = ?
                                                                )
                                                                SELECT
                                                                    quote_ident(c2.relname) as parent_table,
                                                                    pg_get_expr(c1.relpartbound, c1.oid, true) as partition_definition,
                                                                    quote_ident(c1.relname)                    as sub_name,
                                                                    quote_ident(n.nspname)                     as schema_name
                                                                FROM
                                                                    pg_class c1
                                                                JOIN
                                                                    pg_namespace n ON n.oid = c1.relnamespace
                                                                JOIN
                                                                    pg_inherits i ON c1.oid = i.inhrelid
                                                                JOIN
                                                                    pg_class c2 ON i.inhparent = c2.oid
                                                                JOIN
                                                                    PartitionTables pt ON pt.child_schema = n.nspname AND pt.child_table = c1.relname
                                                                WHERE
                                                                    c1.relkind = 'r';""";
    public static final String INDEX_SQL = """
                                           SELECT INDEXDEF, INDEXNAME
                                           FROM pg_indexes
                                           WHERE (schemaname, tablename) = (?, ?)""";
    public static final String TABLE_COLUMN_COMMENT_SQL = """
                                                          SELECT quote_ident(c.relname)       AS table_name,
                                                                 CASE
                                                                     WHEN c.relkind IN ('r', 'p') and a.attname is not null THEN 'COLUMN'
                                                                     WHEN c.relkind IN ('r', 'p') THEN 'TABLE'
                                                                     END                      AS object_type,
                                                                 quote_literal(d.description) AS comment,
                                                                 quote_ident(n.nspname)       AS schema_name,
                                                                 CASE
                                                                     WHEN a.attname IS NOT NULL THEN quote_ident(a.attname)
                                                                     END                      AS column_name
                                                          FROM pg_class c
                                                                   JOIN
                                                               pg_namespace n ON (n.oid = c.relnamespace)
                                                                   LEFT JOIN
                                                               pg_description d ON (c.oid = d.objoid)
                                                                   LEFT JOIN
                                                               pg_attribute a ON (c.oid = a.attrelid AND a.attnum > 0 AND a.attnum = d.objsubid)
                                                          WHERE d.description IS NOT NULL
                                                            AND d.description <> ''
                                                            AND n.nspname = ?
                                                            AND c.relname = ?
                                                          ORDER BY 2 desc;""";
    // MySQL enums (typtype 'l') owned by a column need their name preserved for default casts.
    public static final String COLUMN_SQL = """
                                            SELECT a.*,
                                                   CASE WHEN t.typtype = 'l' AND EXISTS (
                                                       SELECT 1 FROM pg_catalog.pg_depend dep
                                                       WHERE dep.classid = 'pg_catalog.pg_type'::pg_catalog.regclass
                                                         AND dep.objid = t.oid
                                                         AND dep.refclassid = 'pg_catalog.pg_class'::pg_catalog.regclass
                                                         AND dep.refobjid = a.attrelid AND dep.refobjsubid = a.attnum
                                                         AND dep.deptype = 'a'
                                                   ) THEN pg_catalog.format('ENUM(%s) NAMES %I.%I',
                                                       (SELECT pg_catalog.string_agg(pg_catalog.quote_literal(e.enumlabel), ', ' ORDER BY e.enumsortorder)
                                                        FROM pg_catalog.pg_enum e WHERE e.enumtypid = t.oid), tn.nspname, t.typname)
                                                   ELSE pg_catalog.format_type(a.atttypid, a.atttypmod) END AS data_type,
                                                   pg_catalog.pg_get_expr(d.adbin, d.adrelid) AS column_default
                                            FROM pg_catalog.pg_attribute a
                                            JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
                                            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                                            JOIN pg_catalog.pg_type t ON t.oid = a.atttypid
                                            JOIN pg_catalog.pg_namespace tn ON tn.oid = t.typnamespace
                                            LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                                            WHERE n.nspname = ? AND c.relname = ?
                                              AND a.attnum > 0 AND NOT a.attisdropped
                                              AND (pg_catalog.pg_has_role(c.relowner, 'USAGE')
                                                   OR pg_catalog.has_column_privilege(c.oid, a.attnum, 'SELECT, INSERT, UPDATE, REFERENCES'))
                                            ORDER BY a.attnum;""";
    public static final String IDENTITY_SEQUENCE_SQL = """
                                                       SELECT seqstart, seqincrement, seqmin, seqmax, seqcache, seqcycle,
                                                              n.nspname AS sequence_schema, c.relname AS sequence_name
                                                       FROM pg_catalog.pg_sequence s
                                                       JOIN pg_catalog.pg_class c ON c.oid = s.seqrelid
                                                       JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                                                       WHERE seqrelid = pg_catalog.pg_get_serial_sequence(?, ?)::pg_catalog.regclass;
                                                       """;
    public static final String SEQUENCE_STATE_SQL = "SELECT last_value, next_value, is_called FROM %s";
    public static final String TABLE_INDEX_COMMENT_SQL = """
                                                         SELECT quote_ident(n.nspname)                           as schema_name,
                                                                quote_ident(t.relname)                           AS table_name,
                                                                quote_ident(i.relname)                           AS index_name,
                                                                quote_literal(pg_catalog.obj_description(i.oid)) AS index_comment,
                                                                i.oid
                                                         FROM pg_class t
                                                                  INNER JOIN pg_index idx ON t.oid = idx.indrelid
                                                                  INNER JOIN pg_class i ON i.oid = idx.indexrelid
                                                                  INNER JOIN pg_catalog.pg_namespace n ON i.relnamespace = n.oid
                                                         WHERE n.nspname = ?
                                                           AND t.relname = ?
                                                           AND pg_catalog.obj_description(i.oid) IS NOT NULL
                                                           AND pg_catalog.obj_description(i.oid) <> '';""";
    public static final String TABLE_SEQUENCES_COMMENT_SQL = """
                                                             SELECT
                                                                    quote_ident(seq.relname)                           AS sequence_name,
                                                                    quote_literal(pg_catalog.obj_description(seq.oid)) AS sequence_comment
                                                             FROM pg_catalog.pg_class seq
                                                                      JOIN
                                                                  pg_catalog.pg_namespace seq_ns ON seq.relnamespace = seq_ns.oid
                                                                      JOIN
                                                                  pg_catalog.pg_depend dep ON dep.objid = seq.oid
                                                                      JOIN
                                                                  pg_catalog.pg_class tbl ON dep.refobjid = tbl.oid
                                                                      JOIN
                                                                  pg_catalog.pg_namespace tbl_ns ON tbl.relnamespace = tbl_ns.oid
                                                             WHERE seq.relkind = 'S'
                                                               AND seq_ns.nspname = ?
                                                               AND tbl_ns.nspname = ?
                                                               AND tbl.relname = ?
                                                               AND pg_catalog.obj_description(seq.oid) is not null
                                                               AND pg_catalog.obj_description(seq.oid) <> '';""";
    public static final String NORMAL_SUB_TABLE_SQL = """
                                                      -- Read inheritance metadata, including parent and child schemas, names, and OIDs

                                                      WITH inheritance_info AS (
                                                          SELECT p_ns.nspname AS parent_schema,
                                                                 p.relname    AS parent_table,
                                                                 c_ns.nspname AS child_schema,
                                                                 c.relname    AS child_table,
                                                                 p.oid        AS parent_oid,
                                                                 c.oid        AS child_oid
                                                          FROM pg_inherits
                                                                   JOIN pg_class p ON pg_inherits.inhparent = p.oid
                                                                   JOIN pg_class c ON pg_inherits.inhrelid = c.oid
                                                                   JOIN pg_namespace p_ns ON p.relnamespace = p_ns.oid
                                                                   JOIN pg_namespace c_ns ON c.relnamespace = c_ns.oid
                                                          WHERE c_ns.nspname = ? -- Child table schema
                                                            AND c.relname = ? -- Child table name
                                                      ),
                                                      -- Read child columns that are not present in the parent table
                                                           unique_child_columns AS (
                                                               SELECT att.attname AS child_column,
                                                                      ii.child_table,
                                                                      ii.parent_table,
                                                                      ii.parent_schema
                                                               FROM pg_attribute att
                                                                        JOIN pg_class cls ON att.attrelid = cls.oid
                                                                        JOIN inheritance_info ii ON cls.oid = ii.child_oid
                                                                        LEFT JOIN pg_attribute p_att ON att.attname = p_att.attname
                                                                   AND p_att.attrelid = ii.parent_oid
                                                               WHERE att.attnum > 0
                                                                 AND NOT att.attisdropped
                                                                 AND p_att.attname IS NULL -- Exclude columns already present in the parent table
                                                           )
                                                      -- Return custom child columns together with child and parent table metadata
                                                      SELECT
                                                             quote_ident(child_column) as child_column,
                                                             quote_ident(parent_table) as parent_table,
                                                             quote_ident(parent_schema) as parent_schema
                                                      FROM unique_child_columns
                                                      where parent_table is not null
                                                        and parent_table <> ''
                                                      ORDER BY child_table, child_column; -- Sort by child table and column""";
    public static final String TABLE_OPTION_SQL = """
                                                  select
                                                  array_to_string(reloptions, ',') AS table_options
                                                  from pg_class c
                                                           join pg_namespace n on c.relnamespace = n.oid
                                                  where nspname = ?
                                                    and relname = ?;""";
    public static final String TABLE_OWNER_SQL = """
                                                 SELECT
                                                     c.relname AS TABLE_NAME,
                                                     n.nspname AS SCHEMA_NAME,
                                                     r.rolname AS OWNER
                                                 FROM
                                                     pg_class c
                                                 JOIN
                                                     pg_namespace n ON n.oid = c.relnamespace
                                                 JOIN
                                                     pg_roles r ON r.oid = c.relowner
                                                 WHERE
                                                     c.relkind IN ('r', 'p')  -- 'r' is a regular table and 'p' is a partitioned table
                                                     AND n.nspname = ?
                                                     AND c.relname = ?;""";
    public static final String TABLE_PRIVILEGE_SQL = """
                                                     SELECT
                                                         n.nspname AS SCHEMA_NAME,
                                                         c.relname AS TABLE_NAME,
                                                         r.rolname AS OWNER,
                                                         GRANTEE,
                                                         PRIVILEGE_TYPE
                                                     FROM
                                                         pg_class c
                                                     JOIN
                                                         pg_namespace n ON n.oid = c.relnamespace
                                                     JOIN
                                                         pg_roles r ON r.oid = c.relowner
                                                     JOIN
                                                         information_schema.role_table_grants g ON g.table_schema = n.nspname AND g.table_name = c.relname
                                                     WHERE
                                                         c.relkind IN ('r', 'p')  -- 'r' is a regular table, 'p' is a partitioned table, and 'v' is a view
                                                         AND n.nspname = ?
                                                         AND c.relname = ?;""";
    public static final String TRIGGERS_DDL_SQL = """
                                                  SELECT
                                                    pg_get_triggerdef(t.oid) AS trigger_definition
                                                  FROM
                                                    pg_trigger t
                                                    JOIN pg_class c ON t.tgrelid = c.oid
                                                    JOIN pg_namespace n ON c.relnamespace = n.oid
                                                  WHERE
                                                    n.nspname = ?
                                                  """;
    public static final String ROUTINES_DDL_SQL = """
                                                  SELECT
                                                    proname,
                                                    pg_get_functiondef(oid) AS function_definition,
                                                    prokind
                                                  FROM
                                                    pg_proc
                                                  WHERE
                                                    pronamespace = (
                                                      SELECT
                                                        oid
                                                      FROM
                                                        pg_namespace
                                                      WHERE
                                                        nspname = ?
                                                    )
                                                    AND prokind in ('f','p');""";
    public static final String VIEWS_DDL_SQL = "SELECT table_name, view_definition FROM information_schema.views WHERE table_schema = ?";
    public static final String UDT_SQL = """
                                          WITH type_attributes AS (
                                              SELECT
                                                  t.typname AS type_name,
                                                  a.attname AS attribute_name,
                                                  pg_catalog.format_type(a.atttypid, a.atttypmod) AS data_type
                                              FROM
                                                  pg_type t
                                                  JOIN pg_class c ON c.oid = t.typrelid
                                                  JOIN pg_attribute a ON a.attrelid = c.oid
                                                  JOIN pg_namespace n ON n.oid = t.typnamespace
                                              WHERE
                                                  n.nspname = ? -- Requested schema name
                                                  AND t.typtype = 'c'  -- Select composite types only
                                                  AND a.attnum > 0
                                                  AND NOT a.attisdropped
                                                  AND (
                                                      c.relname IS NULL
                                                      OR c.relkind NOT IN ('r', 'S', 'p', 'v') -- Exclude regular tables, sequences, partitioned tables, and views
                                                  )
                                          ),
                                          type_definitions AS (
                                              SELECT
                                                  type_name,
                                                  '    ' || string_agg(attribute_name || ' ' || data_type, E',\\n    ') AS attributes
                                              FROM
                                                  type_attributes
                                              GROUP BY
                                                  type_name
                                          )
                                          SELECT
                                              'CREATE TYPE "' || type_name || '" AS (' || E'\\n' || attributes || E'\\n);' AS create_type_statement,
                                              type_name
                                          FROM
                                              type_definitions;

                                         """;
    public static final String TABLES_SQL = """
                                            WITH partitioned_tables AS (
                                              SELECT
                                                c.oid
                                              FROM
                                                pg_class c
                                                JOIN pg_namespace n ON n.oid = c.relnamespace
                                                JOIN pg_partitioned_table pt ON c.oid = pt.partrelid
                                              WHERE
                                                n.nspname = ?
                                            ),
                                            inherited_tables AS (
                                              SELECT
                                                inhrelid
                                              FROM
                                                pg_inherits
                                              WHERE
                                                inhparent IN (SELECT oid FROM partitioned_tables)
                                            )
                                            SELECT
                                              table_name
                                            FROM
                                              information_schema.tables
                                            WHERE
                                              table_schema = ?
                                              AND table_type = 'BASE TABLE'
                                              AND table_name NOT IN (
                                                SELECT
                                                  c.relname
                                                FROM
                                                  inherited_tables it
                                                  JOIN pg_class c ON it.inhrelid = c.oid
                                              );""";

    public static final String has_parent_table_sql = """
                                                      SELECT
                                                          parent.relname AS parent_table,
                                                          parent_ns.nspname AS parent_schema
                                                      FROM
                                                          pg_inherits
                                                      JOIN
                                                          pg_class child ON pg_inherits.inhrelid = child.oid
                                                      JOIN
                                                          pg_namespace child_ns ON child.relnamespace = child_ns.oid
                                                      JOIN
                                                          pg_class parent ON pg_inherits.inhparent = parent.oid
                                                      JOIN
                                                          pg_namespace parent_ns ON parent.relnamespace = parent_ns.oid
                                                      WHERE
                                                      child_ns.nspname = ? -- Child table schema
                                                      AND child.relname = ?;         -- Child table name
                                                      """;


    public static final String TABLE_ATTRIBUTES_SQL= """
                                                SELECT
                                                	c.relname,
                                                	n.nspname,
                                                	t.spcname,
                                                	d.description,
                                                	u.usename
                                                FROM
                                                	sys_class c
                                                LEFT JOIN sys_namespace n ON
                                                	c.relnamespace = n.oid
                                                LEFT JOIN sys_tablespace t ON
                                                	c.reltablespace = t.oid
                                                LEFT JOIN sys_description d ON
                                                	d.objoid = c.oid
                                                LEFT JOIN sys_user u ON
                                                	c.relowner = u.usesysid
                                                WHERE
                                                	n.nspname = ?
                                                AND	relname = ?
                                                """;
}
