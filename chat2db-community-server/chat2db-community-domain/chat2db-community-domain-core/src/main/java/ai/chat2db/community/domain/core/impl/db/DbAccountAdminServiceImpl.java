package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.account.AccountOperationRequest;
import ai.chat2db.community.domain.api.model.account.AccountExecuteResponse;
import ai.chat2db.community.domain.api.model.account.AccountGrantSummary;
import ai.chat2db.community.domain.api.model.account.AccountInfo;
import ai.chat2db.community.domain.api.model.account.AccountManagerCapability;
import ai.chat2db.community.domain.api.model.account.AccountPreview;
import ai.chat2db.community.domain.api.service.db.IDbAccountAdminService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.IAccountManager;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.util.List;

@Service
public class DbAccountAdminServiceImpl implements IDbAccountAdminService {

    @Override
    public AccountManagerCapability capability() {
        IAccountManager accountManager = requireAccountManager();
        AccountManagerCapability capability = accountManager.capability(requireConnection());
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        capability.setDbType(connectInfo == null ? null : connectInfo.getDbType());
        capability.setConnectionUser(connectInfo == null ? null : connectInfo.getUser());
        return capability;
    }

    @Override
    public List<AccountInfo> listAccounts() {
        return requireAccountManager().listAccounts(requireConnection());
    }

    @Override
    public List<String> showGrants(String user, String host) {
        return requireAccountManager().showGrants(requireConnection(), user, host);
    }

    @Override
    public AccountGrantSummary grantSummary(String user, String host) {
        return requireAccountManager().grantSummary(requireConnection(), user, host);
    }

    @Override
    public AccountPreview preview(AccountOperationRequest command) {
        return requireAccountManager().preview(command);
    }

    @Override
    public AccountExecuteResponse execute(AccountOperationRequest command) {
        return requireAccountManager().execute(requireConnection(), command);
    }

    private IAccountManager requireAccountManager() {
        IAccountManager accountManager = Chat2DBContext.getAccountManager();
        if (accountManager == null) {
            throw new BusinessException("account.manage.unsupported");
        }
        return accountManager;
    }

    private Connection requireConnection() {
        Connection connection = Chat2DBContext.getConnection();
        if (connection == null) {
            throw new BusinessException("connection error");
        }
        return connection;
    }
}
