package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.enums.plugin.AuthenticationTypeEnum;
import ai.chat2db.community.domain.api.model.datasource.DataSourceIdentityColorUtils;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePageQueryRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePreConnectRequest;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.model.datasource.SSLInfo;
import ai.chat2db.community.domain.api.service.db.IDbDataSourceService;
import ai.chat2db.community.domain.api.service.db.IDbWorkspaceDataSourceService;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceStorageFacade;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.exception.DataNotFoundException;
import ai.chat2db.community.tools.exception.NeedLoggedInBusinessException;
import ai.chat2db.community.tools.model.Context;
import ai.chat2db.community.tools.security.AesGcmUtil;
import ai.chat2db.community.tools.util.ConfigUtils;
import ai.chat2db.community.tools.util.ContextUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanInstantiationException;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import ai.chat2db.spi.sql.Chat2DBContext;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
@Slf4j
public class DbWorkspaceDataSourceServiceImpl implements IDbWorkspaceDataSourceService {

    private final IWorkspaceStorageFacade workspaceStorageFacade;
    private final IDbDataSourceService dataSourceService;
    private final DataSourceEnvironmentEnricher environmentEnricher;

    public DbWorkspaceDataSourceServiceImpl(IWorkspaceStorageFacade workspaceStorageFacade,
            IDbDataSourceService dataSourceService,
            DataSourceEnvironmentEnricher environmentEnricher) {
        this.workspaceStorageFacade = workspaceStorageFacade;
        this.dataSourceService = dataSourceService;
        this.environmentEnricher = environmentEnricher;
    }

    @Override
    public PageResponse<WorkspaceDataSource> listDataSources(DbDataSourcePageQueryRequest request) {
        try {
            PageResponse<WorkspaceDataSource> page = workspaceStorageFacade.listDataSources(request);
            return PageResponse.of(environmentEnricher.enrichWorkspaceDataSources(page.getData()),
                    page.getTotal(), page.getPageNo(), page.getPageSize());
        } catch (Exception e) {
            if (request != null && !request.isRefresh()) {
                log.error("datasource.list.fallback", e);
                return PageResponse.empty(request.getPageNo(), request.getPageSize());
            }
            throw e;
        }
    }

    @Override
    public WorkspaceDataSource queryDataSourceById(Long id, Boolean requestPassword) {
        return workspaceStorageFacade.queryDataSourceById(id, requestPassword);
    }

    @Override
    public WorkspaceDataSource queryDisplayDataSourceById(Long id, Boolean requestPassword) {
        WorkspaceDataSource dataSource = environmentEnricher.enrich(queryDataSourceById(id, requestPassword));
        if (Boolean.TRUE.equals(requestPassword)) {
            decryptPassword(dataSource);
        }
        redactSslSecrets(dataSource);
        return dataSource;
    }

    @Override
    public void preConnect(DbDataSourcePreConnectRequest request) {
        WorkspaceDataSource savedDataSource = request.getId() == null ? null
                : queryDataSourceById(request.getId(), true);
        decryptSensitiveFields(savedDataSource);
        if (request.getId() != null
                && (request.getPassword() == null || request.getPassword().isEmpty())
                && savedDataSource != null
                && !AuthenticationTypeEnum.NONE.getCode().equals(request.getAuthenticationType())) {
            request.setPassword(savedDataSource.getPassword());
        }
        // Only an omitted TLS object reuses the saved configuration. An explicit blank object
        // disables TLS and must not resurrect saved private material.
        if (request.getId() != null
                && savedDataSource != null
                && request.getSsl() == null) {
            request.setSsl(savedDataSource.getSsl());
        }
        dataSourceService.preConnect(request);
    }

    @Override
    public WorkspaceDataSource createDataSource(WorkspaceDataSource dataSource) {
        normalizeDataSourceIdentityColor(dataSource);
        validateSupportedDataSource(dataSource);
        applyDefaultDriverConfig(dataSource);
        Long dataSourceId = workspaceStorageFacade.createDataSource(dataSource);
        return queryDisplayDataSourceById(dataSourceId, false);
    }

    @Override
    public WorkspaceDataSource updateDataSource(WorkspaceDataSource dataSource) {
        normalizeDataSourceIdentityColor(dataSource);
        applyDefaultDriverConfig(dataSource);
        workspaceStorageFacade.updateDataSource(dataSource);
        dataSourceService.removeConnection(dataSource.getId());
        return queryDisplayDataSourceById(dataSource.getId(), false);
    }

    @Override
    public WorkspaceDataSource updateDataSourceIdentityColor(Long id, String identityColor) {
        String normalizedIdentityColor = DataSourceIdentityColorUtils.normalize(identityColor);
        workspaceStorageFacade.updateDataSourceIdentityColor(id, normalizedIdentityColor);
        WorkspaceDataSource dataSource = environmentEnricher.enrich(queryDataSourceById(id, false));
        if (dataSource == null) {
            throw new DataNotFoundException();
        }
        return dataSource;
    }

    @Override
    public void deleteDataSource(Long id) {
        workspaceStorageFacade.deleteDataSource(id);
        dataSourceService.removeConnection(id);
    }

    @Override
    public List<WorkspaceDataSource> exportDataSources(List<Long> datasourceIds) {
        List<WorkspaceDataSource> dataSources = new ArrayList<>();
        if (datasourceIds == null || datasourceIds.isEmpty()) {
            DbDataSourcePageQueryRequest request = new DbDataSourcePageQueryRequest();
            request.queryAll();
            PageResponse<WorkspaceDataSource> page = listDataSources(request);
            if (page != null && page.getData() != null) {
                if (ConfigUtils.isOffline()) {
                    return page.getData();
                }
                for (WorkspaceDataSource dataSource : page.getData()) {
                    WorkspaceDataSource withPassword = queryDataSourceById(dataSource.getId(), true);
                    if (withPassword != null) {
                        dataSources.add(withPassword);
                    }
                }
            }
            return dataSources;
        }
        for (Long id : datasourceIds) {
            WorkspaceDataSource dataSource = queryDataSourceById(id, true);
            if (dataSource != null) {
                dataSources.add(dataSource);
            }
        }
        return dataSources;
    }

    @Override
    public List<WorkspaceDataSource> exportDisplayDataSources(List<Long> datasourceIds) {
        return exportDataSources(datasourceIds).stream()
                .map(this::prepareExportDataSource)
                .toList();
    }

    private void validateSupportedDataSource(WorkspaceDataSource dataSource) {
        if (!ConfigUtils.isDesktop()
                && ConfigUtils.isRelease()
                && "LocalFile".equalsIgnoreCase(dataSource.getServiceType())
                && ("H2".equalsIgnoreCase(dataSource.getType())
                || "SQLite".equalsIgnoreCase(dataSource.getType()))) {
            throw new BusinessException("web.not.support.db.type");
        }
    }

    private void normalizeDataSourceIdentityColor(WorkspaceDataSource dataSource) {
        if (dataSource != null) {
            dataSource.setIdentityColor(DataSourceIdentityColorUtils.normalize(dataSource.getIdentityColor()));
        }
    }

    private void applyDefaultDriverConfig(WorkspaceDataSource dataSource) {
        if (dataSource == null) {
            return;
        }
        if (dataSource.getDriverConfig() == null
                || StringUtils.isBlank(dataSource.getDriverConfig().getJdbcDriverClass())) {
            dataSource.setDriverConfig(Chat2DBContext.getDefaultDriverConfig(dataSource.getType()));
        }
    }

    private WorkspaceDataSource prepareExportDataSource(WorkspaceDataSource dataSource) {
        dataSource = copyDataSource(dataSource);
        if (dataSource == null) {
            return null;
        }
        if (ConfigUtils.isCommunity()) {
            dataSource.setPassword(null);
            redactSslSecrets(dataSource);
            dataSource.setSpaceId(null);
            return dataSource;
        }
        if (ConfigUtils.isOffline()) {
            dataSource.setSpaceId(null);
            return dataSource;
        }
        decryptSensitiveFields(dataSource);
        dataSource.setPassword(encryptString(dataSource.getPassword()));
        dataSource.setSpaceId(null);
        return dataSource;
    }

    private WorkspaceDataSource copyDataSource(WorkspaceDataSource dataSource) {
        if (dataSource == null) {
            return null;
        }
        WorkspaceDataSource copy;
        try {
            copy = BeanUtils.instantiateClass(dataSource.getClass());
        } catch (BeanInstantiationException exception) {
            log.debug("Cannot preserve workspace datasource subtype {}.", dataSource.getClass().getName(), exception);
            copy = new WorkspaceDataSource();
        }
        BeanUtils.copyProperties(dataSource, copy);
        return copy;
    }

    private void decryptSensitiveFields(WorkspaceDataSource dataSource) {
        if (dataSource == null) {
            return;
        }
        if ("LOCAL".equalsIgnoreCase(dataSource.getStorageType()) || ConfigUtils.isLocalPersistence()) {
            dataSource.setPassword(decryptString(dataSource.getPassword()));
            decryptSslSensitiveFields(dataSource.getSsl(), false, null);
            return;
        }
        Context context = ContextUtils.queryContext();
        if (context == null || context.getOrganizationToken() == null) {
            throw new NeedLoggedInBusinessException();
        }
        PrivateKey privateKey = stringToPrivateKey(context.getOrganizationToken());
        if (StringUtils.isNotBlank(dataSource.getPassword())) {
            dataSource.setPassword(decryptToken(dataSource.getPassword(), privateKey));
        }
        if (StringUtils.isNotBlank(dataSource.getHost())) {
            dataSource.setHost(decryptToken(dataSource.getHost(), privateKey));
        }
        if (StringUtils.isNotBlank(dataSource.getUrl())) {
            dataSource.setUrl(decryptToken(dataSource.getUrl(), privateKey));
        }
        if (StringUtils.isNotBlank(dataSource.getUser())) {
            dataSource.setUser(decryptToken(dataSource.getUser(), privateKey));
        }
        decryptSslSensitiveFields(dataSource.getSsl(), true, privateKey);
    }

    private void decryptPassword(WorkspaceDataSource dataSource) {
        if (dataSource == null) {
            return;
        }
        if ("LOCAL".equalsIgnoreCase(dataSource.getStorageType()) || ConfigUtils.isLocalPersistence()) {
            dataSource.setPassword(decryptString(dataSource.getPassword()));
            return;
        }
        Context context = ContextUtils.queryContext();
        if (context == null || context.getOrganizationToken() == null) {
            throw new NeedLoggedInBusinessException();
        }
        PrivateKey privateKey = stringToPrivateKey(context.getOrganizationToken());
        if (StringUtils.isNotBlank(dataSource.getPassword())) {
            dataSource.setPassword(decryptToken(dataSource.getPassword(), privateKey));
        }
    }

    private void redactSslSecrets(WorkspaceDataSource dataSource) {
        if (dataSource == null || dataSource.getSsl() == null) {
            return;
        }
        SSLInfo ssl = dataSource.getSsl();
        ssl.setClientPrivateKeyPem(null);
        ssl.setClientKeyPassword(null);
        ssl.setKeyStoreBytes(null);
        ssl.setKeyStorePassword(null);
    }

    /**
     * Decrypt the secret TLS fields. LOCAL storage uses the shared AES key (same AAD as the
     * datasource password); cloud storage uses the organization RSA token. Public material
     * (CA/client cert PEM, keystore type, mode) is stored cleartext and left untouched.
     */
    private void decryptSslSensitiveFields(SSLInfo ssl, boolean cloud, PrivateKey privateKey) {
        if (ssl == null) {
            return;
        }
        ssl.setClientPrivateKeyPem(decryptSecret(ssl.getClientPrivateKeyPem(), cloud, privateKey));
        ssl.setClientKeyPassword(decryptSecret(ssl.getClientKeyPassword(), cloud, privateKey));
        ssl.setKeyStoreBytes(decryptSecret(ssl.getKeyStoreBytes(), cloud, privateKey));
        ssl.setKeyStorePassword(decryptSecret(ssl.getKeyStorePassword(), cloud, privateKey));
    }

    private String decryptSecret(String value, boolean cloud, PrivateKey privateKey) {
        if (StringUtils.isBlank(value)) {
            return value;
        }
        return cloud ? decryptToken(value, privateKey) : decryptString(value);
    }

    private PrivateKey stringToPrivateKey(String privateKeyString) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(privateKeyString);
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePrivate(keySpec);
        } catch (Exception e) {
            throw new BusinessException("api.privateKeyNotFound");
        }
    }

    private String decryptToken(String encryptedToken, PrivateKey privateKey) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            byte[] decryptedBytes = cipher.doFinal(Base64.getDecoder().decode(encryptedToken));
            return new String(decryptedBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("decrypt token error", e);
            throw new BusinessException("api.decryptPasswordError");
        }
    }

    private String decryptString(String password) {
        if (password == null || password.isEmpty()) {
            return password;
        }
        return AesGcmUtil.configured().decrypt(password);
    }

    private String encryptString(String password) {
        if (password == null || password.isEmpty()) {
            return password;
        }
        return AesGcmUtil.configured().encrypt(password);
    }
}
