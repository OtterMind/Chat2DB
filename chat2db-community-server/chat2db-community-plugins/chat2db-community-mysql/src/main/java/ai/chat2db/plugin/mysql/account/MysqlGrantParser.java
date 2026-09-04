package ai.chat2db.plugin.mysql.account;

import ai.chat2db.community.domain.api.enums.plugin.PrivilegeScopeEnum;
import ai.chat2db.community.domain.api.model.account.AccountGrant;
import ai.chat2db.community.domain.api.model.account.AccountGrantSummary;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class MysqlGrantParser {

    static final String SOURCE_DIRECT_ROUTINE = "DIRECT_ROUTINE";
    static final String SOURCE_INHERITED_DATABASE = "INHERITED_DATABASE";
    static final String SOURCE_INHERITED_GLOBAL = "INHERITED_GLOBAL";
    static final String SOURCE_INHERITED_ROLE = "INHERITED_ROLE";
    static final String SOURCE_UNPARSED = "UNPARSED";
    static final String PRIVILEGE_ALL = "ALL_PRIVILEGES";

    private MysqlGrantParser() {
    }

    static AccountGrantSummary readable(List<String> rawStatements) {
        AccountGrantSummary summary = new AccountGrantSummary();
        summary.setReadable(Boolean.TRUE);
        summary.setRawStatements(rawStatements == null ? List.of() : rawStatements);
        summary.setGrants(parse(rawStatements));
        return summary;
    }

    static AccountGrantSummary unreadable(String message) {
        AccountGrantSummary summary = new AccountGrantSummary();
        summary.setReadable(Boolean.FALSE);
        summary.setMessage(message);
        summary.setRawStatements(List.of());
        summary.setGrants(List.of());
        return summary;
    }

    private static List<AccountGrant> parse(List<String> rawStatements) {
        if (rawStatements == null || rawStatements.isEmpty()) {
            return List.of();
        }
        List<AccountGrant> grants = new ArrayList<>();
        for (String rawStatement : rawStatements) {
            AccountGrant grant = parseGrant(rawStatement);
            if (grant != null) {
                grants.add(grant);
            }
        }
        return grants;
    }

    private static AccountGrant parseGrant(String rawStatement) {
        String sql = stripTrailingSemicolon(StringUtils.trimToEmpty(rawStatement));
        if (!startsWithIgnoreCase(sql, "GRANT ")) {
            return unparsed(rawStatement);
        }

        int onIndex = indexOfOutsideLiterals(sql, " ON ", 6);
        int toIndex = indexOfOutsideLiterals(sql, " TO ", onIndex >= 0 ? onIndex + 4 : 6);
        if (onIndex < 0 && toIndex > 0) {
            return roleGrant(rawStatement, sql.substring(6, toIndex).trim());
        }
        if (onIndex < 0 || toIndex < 0) {
            return unparsed(rawStatement);
        }

        List<String> privileges = privileges(sql.substring(6, onIndex));
        String objectPart = sql.substring(onIndex + 4, toIndex).trim();
        boolean grantOption = containsGrantOption(sql.substring(toIndex + 4));

        AccountGrant routineGrant = routineGrant(rawStatement, objectPart, privileges, grantOption);
        if (routineGrant != null) {
            return routineGrant;
        }

        AccountGrant inheritedGrant = inheritedGrant(rawStatement, objectPart, privileges, grantOption);
        if (inheritedGrant != null) {
            return inheritedGrant;
        }

        return unparsed(rawStatement);
    }

    private static AccountGrant routineGrant(String rawStatement, String objectPart, List<String> privileges,
                                             boolean grantOption) {
        String scope;
        String qualifiedName;
        if (startsWithIgnoreCase(objectPart, "FUNCTION ")) {
            scope = PrivilegeScopeEnum.FUNCTION.name();
            qualifiedName = objectPart.substring("FUNCTION ".length()).trim();
        } else if (startsWithIgnoreCase(objectPart, "PROCEDURE ")) {
            scope = PrivilegeScopeEnum.PROCEDURE.name();
            qualifiedName = objectPart.substring("PROCEDURE ".length()).trim();
        } else {
            return null;
        }
        if (!hasRoutinePrivilege(privileges)) {
            return null;
        }

        QualifiedName name = parseQualifiedName(qualifiedName);
        AccountGrant grant = baseGrant(rawStatement, SOURCE_DIRECT_ROUTINE, scope, privileges, grantOption);
        grant.setDatabaseName(name == null ? null : name.databaseName());
        grant.setObjectName(name == null ? null : name.objectName());
        grant.setDirect(Boolean.TRUE);
        grant.setRevocable(Boolean.TRUE);
        return grant;
    }

    private static AccountGrant inheritedGrant(String rawStatement, String objectPart, List<String> privileges,
                                               boolean grantOption) {
        if (!hasRoutinePrivilege(privileges)) {
            return null;
        }
        if ("*.*".equals(objectPart)) {
            AccountGrant grant = baseGrant(rawStatement, SOURCE_INHERITED_GLOBAL, PrivilegeScopeEnum.GLOBAL.name(),
                    privileges, grantOption);
            grant.setDirect(Boolean.FALSE);
            grant.setRevocable(Boolean.FALSE);
            return grant;
        }

        QualifiedName name = parseDatabaseWildcard(objectPart);
        if (name == null) {
            return null;
        }
        AccountGrant grant = baseGrant(rawStatement, SOURCE_INHERITED_DATABASE, PrivilegeScopeEnum.DATABASE.name(),
                privileges, grantOption);
        grant.setDatabaseName(name.databaseName());
        grant.setDirect(Boolean.FALSE);
        grant.setRevocable(Boolean.FALSE);
        return grant;
    }

    private static AccountGrant roleGrant(String rawStatement, String rolePart) {
        AccountGrant grant = baseGrant(rawStatement, SOURCE_INHERITED_ROLE, "ROLE", List.of(), Boolean.FALSE);
        grant.setRoleName(rolePart);
        grant.setDirect(Boolean.FALSE);
        grant.setRevocable(Boolean.FALSE);
        return grant;
    }

    private static AccountGrant unparsed(String rawStatement) {
        AccountGrant grant = baseGrant(rawStatement, SOURCE_UNPARSED, null, List.of(), Boolean.FALSE);
        grant.setDirect(Boolean.FALSE);
        grant.setRevocable(Boolean.FALSE);
        return grant;
    }

    private static AccountGrant baseGrant(String rawStatement, String source, String scope, List<String> privileges,
                                          boolean grantOption) {
        AccountGrant grant = new AccountGrant();
        grant.setSource(source);
        grant.setScope(scope);
        grant.setPrivileges(privileges == null ? List.of() : privileges);
        grant.setGrantOption(grantOption);
        grant.setRawStatement(rawStatement);
        return grant;
    }

    private static List<String> privileges(String privilegePart) {
        if (StringUtils.isBlank(privilegePart)) {
            return List.of();
        }
        List<String> privileges = new ArrayList<>();
        for (String value : privilegePart.split(",")) {
            String normalized = normalizePrivilege(value);
            if (StringUtils.isNotBlank(normalized) && !privileges.contains(normalized)) {
                privileges.add(normalized);
            }
        }
        return privileges;
    }

    private static String normalizePrivilege(String value) {
        String normalized = StringUtils.normalizeSpace(value).toUpperCase(Locale.ROOT);
        if ("ALL".equals(normalized) || "ALL PRIVILEGES".equals(normalized)) {
            return PRIVILEGE_ALL;
        }
        return normalized.replace(' ', '_');
    }

    private static boolean hasRoutinePrivilege(List<String> privileges) {
        return privileges.contains("EXECUTE") || privileges.contains("ALTER_ROUTINE")
                || privileges.contains(PRIVILEGE_ALL);
    }

    private static boolean containsGrantOption(String sqlAfterTo) {
        String clause = " WITH GRANT OPTION";
        int clauseIndex = indexOfOutsideLiterals(sqlAfterTo, clause, 0);
        return clauseIndex >= 0 && StringUtils.isBlank(sqlAfterTo.substring(clauseIndex + clause.length()));
    }

    private static QualifiedName parseDatabaseWildcard(String value) {
        Identifier first = readIdentifier(value, 0);
        if (first == null) {
            return null;
        }
        int index = skipWhitespace(value, first.end());
        if (index >= value.length() || value.charAt(index) != '.') {
            return null;
        }
        index = skipWhitespace(value, index + 1);
        if (index < value.length() && value.charAt(index) == '*') {
            return new QualifiedName(first.value(), null);
        }
        return null;
    }

    private static QualifiedName parseQualifiedName(String value) {
        Identifier database = readIdentifier(value, 0);
        if (database == null) {
            return null;
        }
        int index = skipWhitespace(value, database.end());
        if (index >= value.length() || value.charAt(index) != '.') {
            return null;
        }
        Identifier object = readIdentifier(value, index + 1);
        if (object == null) {
            return null;
        }
        return new QualifiedName(database.value(), object.value());
    }

    private static Identifier readIdentifier(String value, int start) {
        int index = skipWhitespace(value, start);
        if (index >= value.length()) {
            return null;
        }
        if (value.charAt(index) == '`') {
            return readQuotedIdentifier(value, index);
        }
        int end = index;
        while (end < value.length()) {
            char c = value.charAt(end);
            if (c == '.' || Character.isWhitespace(c)) {
                break;
            }
            end++;
        }
        if (end == index) {
            return null;
        }
        return new Identifier(value.substring(index, end), end);
    }

    private static Identifier readQuotedIdentifier(String value, int start) {
        StringBuilder builder = new StringBuilder();
        int index = start + 1;
        while (index < value.length()) {
            char c = value.charAt(index);
            if (c == '`') {
                if (index + 1 < value.length() && value.charAt(index + 1) == '`') {
                    builder.append('`');
                    index += 2;
                    continue;
                }
                return new Identifier(builder.toString(), index + 1);
            }
            builder.append(c);
            index++;
        }
        return null;
    }

    private static int indexOfOutsideLiterals(String value, String needle, int start) {
        String upperValue = value.toUpperCase(Locale.ROOT);
        String upperNeedle = needle.toUpperCase(Locale.ROOT);
        boolean inString = false;
        boolean inIdentifier = false;
        for (int i = Math.max(0, start); i <= value.length() - needle.length(); i++) {
            char c = value.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '\'' && i + 1 < value.length() && value.charAt(i + 1) == '\'') {
                    i++;
                } else if (c == '\'') {
                    inString = false;
                }
                continue;
            }
            if (inIdentifier) {
                if (c == '`' && i + 1 < value.length() && value.charAt(i + 1) == '`') {
                    i++;
                } else if (c == '`') {
                    inIdentifier = false;
                }
                continue;
            }
            if (c == '\'') {
                inString = true;
                continue;
            }
            if (c == '`') {
                inIdentifier = true;
                continue;
            }
            if (upperValue.startsWith(upperNeedle, i)) {
                return i;
            }
        }
        return -1;
    }

    private static int skipWhitespace(String value, int start) {
        int index = Math.max(0, start);
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return index;
    }

    private static String stripTrailingSemicolon(String value) {
        if (StringUtils.endsWith(value, ";")) {
            return value.substring(0, value.length() - 1).trim();
        }
        return value;
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return StringUtils.startsWithIgnoreCase(value, prefix);
    }

    private record Identifier(String value, int end) {
    }

    private record QualifiedName(String databaseName, String objectName) {
    }
}
