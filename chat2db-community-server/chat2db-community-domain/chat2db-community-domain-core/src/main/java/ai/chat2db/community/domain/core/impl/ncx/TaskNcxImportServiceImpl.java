package ai.chat2db.community.domain.core.impl.ncx;

import ai.chat2db.community.domain.core.enums.ncx.DataBaseTypeEnum;
import ai.chat2db.community.domain.core.enums.ncx.VersionEnum;
import ai.chat2db.community.domain.api.enums.file.ConfigFileTypeEnum;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.util.ConfigUtils;
import ai.chat2db.community.domain.api.model.ncx.NcxImportResponse;
import ai.chat2db.community.domain.api.model.datasource.DataSourceIdentityColorUtils;
import ai.chat2db.community.domain.api.service.task.ITaskNcxImportService;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceStorageFacade;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.core.impl.ncx.cipher.CommonCipher;
import ai.chat2db.community.domain.core.impl.ncx.dbeaver.DbeaverCredentialsResolver;
import ai.chat2db.community.domain.api.model.datasource.SSHInfo;
import cn.hutool.core.io.FileUtil;
import com.alibaba.excel.util.FileUtils;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;


@Service
@Slf4j
public class TaskNcxImportServiceImpl implements ITaskNcxImportService {

    private static final double NAVICAT11 = 1.1D;

    private static CommonCipher cipher;


    private static final String DATASOURCE_SETTINGS = "#DataSourceSettings#";
    private static final String XML_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";


    private static final String BEGIN = "#BEGIN#";


    private static final String connection = "#connection";


    public static final Pattern IP_PORT = Pattern.compile("jdbc:(?<type>[a-z]+)://(?<host>[a-zA-Z0-9-//.]+):(?<port>[0-9]+)");


    public static final Pattern ORACLE_IP_PORT = Pattern.compile("jdbc:(?<type>[a-z]+):(?<child>[a-z]+):@(?<host>[a-zA-Z0-9-//.]+):(?<port>[0-9]+)");

    private final IWorkspaceStorageFacade workspaceStorageFacade;

    public TaskNcxImportServiceImpl(IWorkspaceStorageFacade workspaceStorageFacade) {
        this.workspaceStorageFacade = workspaceStorageFacade;
    }

    @Override
    public NcxImportResponse ncxUploadFile(File file) {
        NcxImportResponse vo = new NcxImportResponse();
        try {
            List<Map<String, Map<String, String>>> configMap = new ArrayList<>();
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document document = db.parse(file);
            NodeList connectList = document.getElementsByTagName("Connection");

            NodeList nodeList = document.getElementsByTagName("Connections");
            NamedNodeMap verMap = nodeList.item(0).getAttributes();
            double version = Double.parseDouble((verMap.getNamedItem("Ver").getNodeValue()));
            if (version <= NAVICAT11) {
                cipher = CipherFactory.get(VersionEnum.native11.name());
            } else {
                cipher = CipherFactory.get(VersionEnum.navicat12more.name());
            }
            Map<String, Map<String, String>> connectionMap = new HashMap<>();
            for (int i = 0; i < connectList.getLength(); i++) {
                Node connect = connectList.item(i);
                NamedNodeMap attrs = connect.getAttributes();
                Map<String, String> map = new HashMap<>(0);
                for (int j = 0; j < attrs.getLength(); j++) {
                    Node attr = attrs.item(j);
                    map.put(attr.getNodeName(), attr.getNodeValue());
                }
                connectionMap.put(map.get("ConnectionName") + map.get("ConnType"), map);
            }
            configMap.add(connectionMap);
            int n = insertDBConfig(configMap);
            vo.setCount(n);
            log.info("insert to h2 success");
            FileUtils.delete(file);
        } catch (Exception e) {
            vo.setResult("Error: " + e.getMessage());
        }
        return vo;
    }

    @SneakyThrows
    @Override
    public NcxImportResponse dbpUploadFile(File file, String masterPassword) {
        NcxImportResponse vo = new NcxImportResponse();
        Document metaTree;
        int n = 0;
        List<String> projects = new ArrayList<>();
        // Request-scoped: each project's credential file is decrypted once and never outlives this import.
        DbeaverCredentialsResolver credentialsResolver = new DbeaverCredentialsResolver(masterPassword);
        try (ZipFile zipFile = new ZipFile(file, ZipFile.OPEN_READ)) {
            ZipEntry metaEntry = zipFile.getEntry(ExportConstants.META_FILENAME);
            if (metaEntry == null) {
                vo.setResult("Cannot find meta file.");
                return vo;
            }
            try (InputStream metaStream = zipFile.getInputStream(metaEntry)) {
                metaTree = XMLUtils.parseDocument(metaStream);
            } catch (Exception e) { // impl-contract: fallback - import response carries the parse failure message.
                vo.setResult("Cannot parse meta file: " + e.getMessage());
                return vo;
            }

            Element projectsElement = XMLUtils.getChildElement(metaTree.getDocumentElement(), ExportConstants.TAG_PROJECTS);
            if (projectsElement != null) {
                final Collection<Element> projectList = XMLUtils.getChildElementList(projectsElement, ExportConstants.TAG_PROJECT);
                for (Element projectElement : projectList) {
                    String projectName = projectElement.getAttribute(ExportConstants.ATTR_NAME);
                    String config = ConfigUtils.getBasePath() + File.separator + projectName + File.separator + ExportConstants.CONFIG_FILE;
                    // Registered before extraction so a failure mid-import still removes the extracted copy.
                    projects.add(projectName);
                    importDbeaverConfig(new File(config),
                            projectElement,
                            ExportConstants.DIR_PROJECTS + "/" + projectName + "/",
                            zipFile);
                    JSONObject credentialsJson = credentialsResolver.resolve(
                            new File(config + File.separator + ExportConstants.CONFIG_CREDENTIALS_FILE));
                    File json = new File(config + File.separator + ExportConstants.CONFIG_DATASOURCE_FILE);
                    JSONObject jsonObject;
                    try (InputStream dataSourceStream = new FileInputStream(json)) {
                        jsonObject = JSON.parseObject(dataSourceStream);
                    }
                    JSONObject connections = jsonObject.getJSONObject(ExportConstants.DIR_CONNECTIONS);
                    Set<String> keys = connections.keySet();
                    for (String key : keys) {
                        JSONObject configurations = connections.getJSONObject(key);
                        JSONObject configuration = configurations.getJSONObject(ExportConstants.DIR_CONFIGURATION);
                        String provider = configurations.getString("provider");
                        if (provider.equals(ExportConstants.GENERIC)) {
                            JSONObject drivers = jsonObject.getJSONObject(ExportConstants.DIR_DRIVERS);
                            String driverId = configurations.getString("driver");
                            JSONObject generics = drivers.getJSONObject(provider);
                            JSONObject generic = generics.getJSONObject(driverId);
                            if (null == generic) {
                                continue;
                            }
                            provider = generic.getString("name");
                        }
                        DataBaseTypeEnum dataBaseType = DataBaseTypeEnum.matchType(provider.toUpperCase());
                        WorkspaceDataSource dataSourceDO;
                        if (null != dataBaseType) {
                            dataSourceDO = new WorkspaceDataSource();
                            dataSourceDO.setAlias(configurations.getString("name"));
                            dataSourceDO.setHost(configuration.getString("host"));
                            dataSourceDO.setPort(configuration.getString("port"));
                            dataSourceDO.setUrl(configuration.getString("url"));
                            SSHInfo sshInfo = new SSHInfo();
                            sshInfo.setUse(false);
                            dataSourceDO.setSsh(sshInfo);
                            applyDbeaverCredentials(dataSourceDO, credentialsJson, key);
                            dataSourceDO.setType(dataBaseType.name());
                            n++;
                            insertDatasource(dataSourceDO);
                        }
                    }
                }
            }
        } finally {
            // Extracted projects hold decrypted credentials, so remove every one of them and the upload.
            FileUtils.delete(file);
            projects.forEach(v -> FileUtils.delete(new File(ConfigUtils.getBasePath() + File.separator + v)));
        }
        vo.setCount(n);
        return vo;
    }

    @SneakyThrows
    @Override
    public NcxImportResponse datagripUploadFile(String text) {
        NcxImportResponse vo = new NcxImportResponse();
        if (!text.startsWith(DATASOURCE_SETTINGS)) {
            vo.setResult("Connection information header is incorrect!");
            return vo;
        }
        String[] items = text.split("\n");
        List<String> configs = new ArrayList<>();
        for (int i = 0; i < items.length; i++) {
            if (items[i].equals(BEGIN)) {
                configs.add(XML_HEADER + items[i + 1]);
            }
        }
        int n = 0;
        for (String config : configs) {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            try (InputStream inputStream = new ByteArrayInputStream(config.getBytes(StandardCharsets.UTF_8))) {
                Document document = db.parse(inputStream);
                Element rootElement = document.getDocumentElement();
                WorkspaceDataSource dataSourceDO = new WorkspaceDataSource();
                dataSourceDO.setAlias(rootElement.getAttribute("name"));
                Element databaseInfoElement = (Element) rootElement.getElementsByTagName("database-info").item(0);
                String type = databaseInfoElement.getAttribute("dbms");
                String jdbcUrl = rootElement.getElementsByTagName("jdbc-url").item(0).getTextContent();
                String username = rootElement.getElementsByTagName("user-name").item(0).getTextContent();
                String driverName = rootElement.getElementsByTagName("jdbc-driver").item(0).getTextContent();
                String host = "";
                String port = "";
                if (type.equals(DataBaseTypeEnum.ORACLE.name())) {
                    Matcher matcher = ORACLE_IP_PORT.matcher(jdbcUrl);
                    if (matcher.find()) {
                        host = matcher.group("host");
                        port = matcher.group("port");
                    }
                } else {
                    Matcher matcher = IP_PORT.matcher(jdbcUrl);
                    if (matcher.find()) {
                        host = matcher.group("host");
                        port = matcher.group("port");

                    }
                }
                SSHInfo sshInfo = new SSHInfo();
                sshInfo.setUse(false);
                dataSourceDO.setSsh(sshInfo);
                dataSourceDO.setHost(host);
                dataSourceDO.setPort(port);
                dataSourceDO.setUrl(jdbcUrl);
                dataSourceDO.setUser(username);
                dataSourceDO.setDriver(driverName);
                dataSourceDO.setType(DataBaseTypeEnum.matchType(jdbcUrl).name());
                n++;
                insertDatasource(dataSourceDO);
            }
        }
        vo.setCount(n);
        return vo;
    }

    @Override
    public NcxImportResponse chat2dbUploadFile(File file) {
        NcxImportResponse uploadResponse = new NcxImportResponse();
        String content = FileUtil.readUtf8String(file);
        if (StringUtils.isEmpty(content)) {
            uploadResponse.setResult("File content is empty!");
            return uploadResponse;
        }
        List<WorkspaceDataSource> dataSourceResponses = JSON.parseArray(content, WorkspaceDataSource.class);
        if (CollectionUtils.isNotEmpty(dataSourceResponses)) {
            uploadResponse.setCount(dataSourceResponses.size());
            dataSourceResponses.forEach(dataSourceResponse -> {
                dataSourceResponse.setId(null);
                dataSourceResponse.setSpaceId(null);
                dataSourceResponse.setPassword(null);
                insertDatasource(dataSourceResponse);
            });
        }

        return uploadResponse;
    }

    @Override
    public NcxImportResponse uploadFile(File file, ConfigFileTypeEnum fileType) {
        if (ConfigFileTypeEnum.NCX == fileType) {
            return ncxUploadFile(file);
        }
        if (ConfigFileTypeEnum.DBP == fileType) {
            return dbpUploadFile(file, null);
        }
        if (ConfigFileTypeEnum.JSON == fileType) {
            return chat2dbUploadFile(file);
        }
        throw new BusinessException("file.type.unsupported");
    }


    @SneakyThrows
    public int insertDBConfig(List<Map<String, Map<String, String>>> list) {
        int n = 0;
        for (Map<String, Map<String, String>> map : list) {
            for (Map.Entry<String, Map<String, String>> valueMap : map.entrySet()) {
                Map<String, String> resultMap = valueMap.getValue();
                DataBaseTypeEnum dataBaseType = DataBaseTypeEnum.matchType(resultMap.get("ConnType"));
                WorkspaceDataSource dataSourceDO;
                if (null == dataBaseType) {
                    continue;
                } else {
                    dataSourceDO = new WorkspaceDataSource();
                    dataSourceDO.setHost(resultMap.get("Host"));
                    dataSourceDO.setPort(resultMap.get("Port"));
                    dataSourceDO.setUrl(String.format(dataBaseType.getUrlString(), dataSourceDO.getHost(), dataSourceDO.getPort()));
                }
                String password = cipher.decryptString(resultMap.getOrDefault("Password", ""));
                dataSourceDO.setAlias(resultMap.get("ConnectionName"));
                dataSourceDO.setUser(resultMap.get("UserName"));
                dataSourceDO.setType(resultMap.get("ConnType"));
                dataSourceDO.setPassword(password);
                SSHInfo sshInfo = new SSHInfo();
                if ("false".equals(resultMap.get("SSH"))) {
                    sshInfo.setUse(false);
                } else {
                    sshInfo.setUse(true);
                    sshInfo.setHostName(resultMap.get("SSH_Host"));
                    sshInfo.setPort(resultMap.get("SSH_Port"));
                    sshInfo.setUserName(resultMap.get("SSH_UserName"));
                    boolean passwordType = "password".equalsIgnoreCase(resultMap.get("SSH_AuthenMethod"));
                    sshInfo.setAuthenticationType(passwordType ? "password" : "Private key");
                    if (passwordType) {
                        String ssh_password = cipher.decryptString(resultMap.getOrDefault("SSH_Password", ""));
                        sshInfo.setPassword(ssh_password);
                    } else {
                        sshInfo.setKeyFile(resultMap.get("SSH_PrivateKey"));
                        sshInfo.setPassphrase(resultMap.get("SSH_Passphrase"));
                    }
                }
                dataSourceDO.setSsh(sshInfo);
                n++;
                insertDatasource(dataSourceDO);
            }
        }
        return n;
    }

    @SneakyThrows
    private static void importDbeaverConfig(File resource, Element resourceElement, String containerPath, ZipFile zipFile) {
        for (Element childElement : XMLUtils.getChildElementList(resourceElement, ExportConstants.TAG_RESOURCE)) {
            String childName = childElement.getAttribute(ExportConstants.ATTR_NAME);
            String entryPath = containerPath + childName;
            ZipEntry resourceEntry = zipFile.getEntry(entryPath);
            if (resourceEntry == null) {
                continue;
            }
            boolean isDirectory = resourceEntry.isDirectory();
            if (isDirectory) {
                File folder = new File(resource.getPath());
                if (!folder.exists()) {
                    FileUtil.mkdir(folder);
                }
                importDbeaverConfig(folder, childElement, entryPath + "/", zipFile);
            } else {
                File file = new File(resource.getPath() + File.separator + childName);
                FileUtil.writeFromStream(zipFile.getInputStream(resourceEntry), file, true);
            }
        }
    }

    /**
     * Copies the user and password of one DBeaver connection out of the project's decrypted
     * credentials. A connection legitimately has no entry when "save password" was disabled for it,
     * and the whole document is absent when it could not be decrypted, so both cases leave the
     * datasource without credentials rather than failing the import.
     *
     * @param dataSource      datasource being imported.
     * @param credentialsJson decrypted credentials of the owning project, may be null.
     * @param connectionId    DBeaver connection id used as the credential key.
     */
    private static void applyDbeaverCredentials(WorkspaceDataSource dataSource, JSONObject credentialsJson, String connectionId) {
        if (null == credentialsJson) {
            return;
        }
        JSONObject userPassword;
        try {
            JSONObject userInfo = credentialsJson.getJSONObject(connectionId);
            userPassword = null == userInfo ? null : userInfo.getJSONObject(connection);
        } catch (Exception e) { // impl-contract: fallback - a malformed credential entry imports that connection without a password.
            log.warn("Skipping malformed DBeaver credential entry: {}", e.getMessage());
            return;
        }
        if (null == userPassword) {
            return;
        }
        dataSource.setUser(userPassword.getString("user"));
        dataSource.setPassword(userPassword.getString("password"));
    }

    private void insertDatasource(WorkspaceDataSource request) {
        if (request.getEnvironmentId() == null) {
            request.setEnvironmentId(1L);
        }
        request.setIdentityColor(DataSourceIdentityColorUtils.normalize(request.getIdentityColor()));
        workspaceStorageFacade.createDataSource(request);
    }
}
