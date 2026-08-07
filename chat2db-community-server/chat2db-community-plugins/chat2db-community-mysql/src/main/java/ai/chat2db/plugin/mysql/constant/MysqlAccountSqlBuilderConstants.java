package ai.chat2db.plugin.mysql.constant;

import ai.chat2db.spi.constant.SQLConstants;

import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.domain.api.enums.plugin.AccountActionTypeEnum;
import ai.chat2db.community.domain.api.enums.plugin.PrivilegeScopeEnum;
import ai.chat2db.community.domain.api.model.account.AccountOperationRequest;
import ai.chat2db.plugin.mysql.enums.account.MysqlPrivilege;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;



public final class MysqlAccountSqlBuilderConstants {

    public static final String ERROR_KEY_ACCOUNT_ACTION_UNSUPPORTED = "mysql.account.actionUnsupported";
    public static final String MASKED_PASSWORD_LITERAL = "'******'";
    public static final String SHA_256_ALGORITHM = "SHA-256";
    public static final String HEX_BYTE_FORMAT = "%02x";
    public static final String ERROR_KEY_ACCOUNT_USER_REQUIRED = "mysql.account.userRequired";
    public static final String ERROR_KEY_ACCOUNT_HOST_REQUIRED = "mysql.account.hostRequired";
    public static final String ERROR_KEY_ACCOUNT_IDENTIFIER_REQUIRED = "mysql.account.identifierRequired";
    public static final String ESCAPED_BACK_QUOTE = "``";
    public static final String ERROR_KEY_ACCOUNT_LITERAL_REQUIRED = "mysql.account.literalRequired";
    public static final String ESCAPED_BACKSLASH = "\\\\";
    public static final String ESCAPED_SINGLE_QUOTE = "''";
    public static final String ERROR_KEY_ACCOUNT_DATABASE_REQUIRED = "mysql.account.databaseRequired";
    public static final String ERROR_KEY_ACCOUNT_TABLE_REQUIRED = "mysql.account.tableRequired";
    public static final String ERROR_KEY_ACCOUNT_SCOPE_UNSUPPORTED = "mysql.account.scopeUnsupported";
    public static final String ERROR_KEY_ACCOUNT_PRIVILEGE_REQUIRED = "mysql.account.privilegeRequired";
    public static final String ERROR_KEY_ACCOUNT_OBJECT_REQUIRED = "mysql.account.objectRequired";
    public static final String ERROR_KEY_ACCOUNT_ROUTINE_PRIVILEGE_UNSUPPORTED = "mysql.account.routinePrivilegeUnsupported";
    public static final String ERROR_KEY_ACCOUNT_ACTION_REQUIRED = "mysql.account.actionRequired";
    public static final String ERROR_KEY_ACCOUNT_PASSWORD_REQUIRED = "mysql.account.passwordRequired";
    public static final String ERROR_KEY_ACCOUNT_INVALID_ACCOUNT_NAME = "mysql.account.invalidAccountName";

    private MysqlAccountSqlBuilderConstants() {
    }
}
