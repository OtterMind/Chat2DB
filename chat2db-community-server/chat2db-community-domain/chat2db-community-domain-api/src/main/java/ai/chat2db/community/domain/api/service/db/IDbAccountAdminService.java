package ai.chat2db.community.domain.api.service.db;

import ai.chat2db.community.domain.api.model.account.AccountOperationRequest;
import ai.chat2db.community.domain.api.model.account.AccountExecuteResponse;
import ai.chat2db.community.domain.api.model.account.AccountGrantSummary;
import ai.chat2db.community.domain.api.model.account.AccountInfo;
import ai.chat2db.community.domain.api.model.account.AccountManagerCapability;
import ai.chat2db.community.domain.api.model.account.AccountPreview;

import java.util.List;

/**
 * Manages database account administration capabilities and SQL command execution.
 */
public interface IDbAccountAdminService {

    /**
     * Returns account-management features supported by the current datasource.
     *
     * @return account manager capability metadata.
     */
    AccountManagerCapability capability();

    /**
     * Lists database accounts visible to the current datasource connection.
     *
     * @return database account information.
     */
    List<AccountInfo> listAccounts();

    /**
     * Lists grants for a database account.
     *
     * @param user account user name.
     * @param host account host name.
     * @return grant statements for the account.
     */
    List<String> showGrants(String user, String host);

    /**
     * Summarizes grants for a database account with source labels.
     *
     * @param user account user name.
     * @param host account host name.
     * @return parsed grant summary for the account.
     */
    AccountGrantSummary grantSummary(String user, String host);

    /**
     * Previews SQL commands for an account-management operation.
     *
     * @param accountOperationRequest account-management operation to preview.
     * @return generated SQL preview for the command.
     */
    AccountPreview preview(AccountOperationRequest accountOperationRequest);

    /**
     * Executes an account-management operation.
     *
     * @param accountOperationRequest account-management operation to execute.
     * @return execution result for the command.
     */
    AccountExecuteResponse execute(AccountOperationRequest accountOperationRequest);
}
