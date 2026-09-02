package ai.chat2db.spi;

import ai.chat2db.community.domain.api.model.account.AccountOperationRequest;
import ai.chat2db.community.domain.api.model.account.AccountExecuteResponse;
import ai.chat2db.community.domain.api.model.account.AccountInfo;
import ai.chat2db.community.domain.api.model.account.AccountManagerCapability;
import ai.chat2db.community.domain.api.model.account.AccountPreview;

import java.sql.Connection;
import java.util.List;


public interface IAccountManager {

    AccountManagerCapability capability(Connection connection);

    List<AccountInfo> listAccounts(Connection connection);

    List<String> showGrants(Connection connection, String user, String host);

    AccountPreview preview(Connection connection, AccountOperationRequest accountOperationRequest);

    AccountExecuteResponse execute(Connection connection, AccountOperationRequest accountOperationRequest);
}
