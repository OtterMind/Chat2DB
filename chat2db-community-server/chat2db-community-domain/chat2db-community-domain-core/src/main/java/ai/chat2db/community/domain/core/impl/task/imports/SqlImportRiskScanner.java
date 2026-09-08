package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.ImportAdmissionFinding;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streaming lexical risk scan for SQL import scripts. This is intentionally not an execution
 * parser: it strips comments and literals, records only facts needed by the admission gate, and
 * treats ambiguous scripts conservatively.
 */
final class SqlImportRiskScanner {

    private static final long DEFAULT_MAX_STATEMENT_CHARS = 32L * 1024L * 1024L;

    private static final Pattern USER_VARIABLE_DEFINITION =
            Pattern.compile("(?:^|\\s)SET\\s+@([A-Z0-9_$]+)\\s*=");
    private static final Pattern USER_VARIABLE_REFERENCE = Pattern.compile("@([A-Z0-9_$]+)");
    private static final Pattern TEMP_TABLE = Pattern.compile(
            "(?:^|\\s)CREATE\\s+(?:(?:GLOBAL|LOCAL)\\s+)?TEMP(?:ORARY)?\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([A-Z0-9_$.]+)");

    private SqlImportRiskScanner() {
    }

    static List<ImportAdmissionFinding> scan(File source, Charset charset) throws IOException {
        State state = new State(Long.getLong("chat2db.task.import.parallel.max-statement-chars",
                DEFAULT_MAX_STATEMENT_CHARS));
        try (BufferedReader reader = Files.newBufferedReader(source.toPath(), charset)) {
            LexicalState lexicalState = LexicalState.NORMAL;
            boolean escaped = false;
            int current;
            int previous = -1;
            while ((current = reader.read()) != -1) {
                char value = (char) current;
                if (lexicalState == LexicalState.LINE_COMMENT) {
                    if (value == '\n' || value == '\r') {
                        lexicalState = LexicalState.NORMAL;
                        state.append(' ');
                    }
                    previous = current;
                    continue;
                }
                if (lexicalState == LexicalState.BLOCK_COMMENT) {
                    if (previous == '*' && value == '/') {
                        lexicalState = LexicalState.NORMAL;
                        state.append(' ');
                    }
                    previous = current;
                    continue;
                }
                if (lexicalState != LexicalState.NORMAL) {
                    if (escaped) {
                        escaped = false;
                    } else if (value == '\\') {
                        escaped = true;
                    } else if (isClosingQuote(lexicalState, value)) {
                        lexicalState = LexicalState.NORMAL;
                    }
                    state.append(' ');
                    previous = current;
                    continue;
                }

                if (previous == '-' && value == '-') {
                    state.removeLast();
                    lexicalState = LexicalState.LINE_COMMENT;
                } else if (value == '#') {
                    lexicalState = LexicalState.LINE_COMMENT;
                } else if (previous == '/' && value == '*') {
                    state.removeLast();
                    lexicalState = LexicalState.BLOCK_COMMENT;
                } else if (value == '\'') {
                    lexicalState = LexicalState.SINGLE_QUOTE;
                    state.append(' ');
                } else if (value == '"') {
                    lexicalState = LexicalState.DOUBLE_QUOTE;
                    state.append(' ');
                } else if (value == '`') {
                    lexicalState = LexicalState.BACKTICK;
                    state.append(' ');
                } else if (value == ';') {
                    state.finishStatement();
                } else {
                    state.append(value);
                }
                previous = current;
            }
            state.finishStatement();
        }
        return state.findings();
    }

    private static boolean isClosingQuote(LexicalState state, char value) {
        return state == LexicalState.SINGLE_QUOTE && value == '\''
                || state == LexicalState.DOUBLE_QUOTE && value == '"'
                || state == LexicalState.BACKTICK && value == '`';
    }

    private enum LexicalState {
        NORMAL,
        SINGLE_QUOTE,
        DOUBLE_QUOTE,
        BACKTICK,
        LINE_COMMENT,
        BLOCK_COMMENT
    }

    private static final class State {
        private final long maxStatementChars;
        private final StringBuilder statement = new StringBuilder();
        private final List<ImportAdmissionFinding> findings = new ArrayList<>();
        private final Set<String> emittedCodes = new HashSet<>();
        private final Set<String> definedVariables = new HashSet<>();
        private final Set<String> temporaryTables = new HashSet<>();
        private boolean ddlSeen;
        private boolean sessionSwitchSeen;
        private long statementNumber;

        private State(long maxStatementChars) {
            this.maxStatementChars = Math.max(1L, maxStatementChars);
        }

        private void append(char value) {
            statement.append(value);
            if (statement.length() > maxStatementChars) {
                finding("C1", "A SQL statement exceeds the safe sharding threshold",
                        "Statement " + (statementNumber + 1L) + " exceeds " + maxStatementChars + " characters",
                        "Split the atomic statement at a semantic boundary or use SERIAL_SAFE mode.");
            }
        }

        private void removeLast() {
            if (!statement.isEmpty()) {
                statement.setLength(statement.length() - 1);
            }
        }

        private void finishStatement() {
            String normalized = StringUtils.normalizeSpace(statement.toString()).toUpperCase(Locale.ROOT);
            statement.setLength(0);
            if (normalized.isEmpty()) {
                return;
            }
            statementNumber++;
            boolean ddl = startsWithAny(normalized, "CREATE ", "ALTER ", "DROP ", "TRUNCATE ");
            boolean dml = startsWithAny(normalized, "INSERT ", "UPDATE ", "DELETE ", "MERGE ", "COPY ");

            Matcher references = USER_VARIABLE_REFERENCE.matcher(normalized);
            while (references.find()) {
                String name = references.group(1);
                if (definedVariables.contains(name) && !normalized.startsWith("SET @" + name + "=")) {
                    finding("A1", "A user variable is defined and consumed across SQL statements",
                            "Variable @" + name + " is referenced after its definition",
                            "Preserve statement order on one connection or replace the variable with explicit values.");
                }
            }
            Matcher definitions = USER_VARIABLE_DEFINITION.matcher(normalized);
            while (definitions.find()) {
                definedVariables.add(definitions.group(1));
            }

            Matcher temporaryTable = TEMP_TABLE.matcher(normalized);
            if (temporaryTable.find()) {
                temporaryTables.add(temporaryTable.group(1));
            } else {
                for (String table : temporaryTables) {
                    if (containsIdentifier(normalized, table)) {
                        finding("A2", "A temporary table is referenced across SQL statements",
                                "Temporary table " + table + " is used after creation",
                                "Run the script serially on one connection or materialize a staging table.");
                    }
                }
            }

            if (isSessionSwitch(normalized)) {
                sessionSwitchSeen = true;
            } else if (sessionSwitchSeen && (ddl || dml)) {
                finding("A3", "Later statements depend on a connection-scoped session switch",
                        "A session/global switch appears before statement " + statementNumber,
                        "Run the script serially on one connection or remove and model the switch explicitly.");
            }

            if (ddlSeen && dml) {
                finding("A4", "DDL and dependent DML are mixed in one import script",
                        "DML appears after DDL at statement " + statementNumber,
                        "Apply schema changes separately before generating a data-only import plan.");
            }
            ddlSeen |= ddl;

            if (containsAny(normalized, "LOCK TABLES", "LOCK TABLE ", "DISABLE KEYS", "ENABLE KEYS")) {
                finding("A5", "The script contains table-lock or key-disable semantics",
                        "Lock/key state is connection and order sensitive",
                        "Remove lock wrappers during preprocessing or use SERIAL_SAFE mode.");
            }
            if (containsAny(normalized, "LAST_INSERT_ID(", "LASTVAL(", "CURRVAL(")) {
                finding("B2", "The script reads a connection-local generated identifier",
                        "Generated-ID chaining appears in statement " + statementNumber,
                        "Export explicit primary and foreign key values or use SERIAL_SAFE mode.");
            }
        }

        private List<ImportAdmissionFinding> findings() {
            return List.copyOf(findings);
        }

        private void finding(String code, String message, String evidence, String remediation) {
            if (emittedCodes.add(code)) {
                findings.add(ImportAdmissionFinding.builder().code(code).severity("BLOCKER")
                        .message(message).evidence(evidence).remediation(remediation).build());
            }
        }

        private boolean isSessionSwitch(String sql) {
            return sql.startsWith("SET ") && containsAny(sql,
                    "FOREIGN_KEY_CHECKS", "UNIQUE_CHECKS", "SQL_MODE", "AUTOCOMMIT",
                    "SESSION_REPLICATION_ROLE", "CONSTRAINTS ALL", "SYNCHRONOUS_COMMIT");
        }

        private boolean startsWithAny(String value, String... candidates) {
            for (String candidate : candidates) {
                if (value.startsWith(candidate)) {
                    return true;
                }
            }
            return false;
        }

        private boolean containsAny(String value, String... candidates) {
            for (String candidate : candidates) {
                if (value.contains(candidate)) {
                    return true;
                }
            }
            return false;
        }

        private boolean containsIdentifier(String sql, String identifier) {
            return Pattern.compile("(^|[^A-Z0-9_$])" + Pattern.quote(identifier) + "([^A-Z0-9_$]|$)")
                    .matcher(sql).find();
        }
    }
}
