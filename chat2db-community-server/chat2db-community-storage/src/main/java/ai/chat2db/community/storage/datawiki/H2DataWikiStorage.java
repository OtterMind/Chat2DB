package ai.chat2db.community.storage.datawiki;

import ai.chat2db.community.domain.api.model.datawiki.DataWikiDefinition;
import ai.chat2db.community.domain.api.model.datawiki.DataWikiResource;
import ai.chat2db.community.domain.api.service.storage.IDataWikiStorage;
import ai.chat2db.community.tools.util.ConfigUtils;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.List;

@Component
public class H2DataWikiStorage implements IDataWikiStorage {

    private static final String MIGRATION_LOCATION = "classpath:db/datawiki/migration";
    private final DataSource dataSource;

    public H2DataWikiStorage() {
        this(defaultDataSource());
    }

    H2DataWikiStorage(DataSource dataSource) {
        this.dataSource = dataSource;
        Flyway.configure(H2DataWikiStorage.class.getClassLoader())
                .dataSource(dataSource)
                .locations(MIGRATION_LOCATION)
                .load()
                .migrate();
    }

    @Override
    public DataWikiDefinition create(DataWikiDefinition dataWiki) {
        String sql = "INSERT INTO datawiki_definition "
                + "(id, name, description, resources_json, created_by, created_at, updated_at, revision) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, dataWiki);
            statement.executeUpdate();
            return get(dataWiki.getId());
        } catch (SQLException exception) {
            throw failure("create DataWiki", exception);
        }
    }

    @Override
    public DataWikiDefinition get(String id) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM datawiki_definition WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? read(resultSet) : null;
            }
        } catch (SQLException exception) {
            throw failure("get DataWiki", exception);
        }
    }

    @Override
    public List<DataWikiDefinition> list() {
        List<DataWikiDefinition> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM datawiki_definition ORDER BY updated_at DESC");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) result.add(read(resultSet));
            return result;
        } catch (SQLException exception) {
            throw failure("list DataWikis", exception);
        }
    }

    @Override
    public DataWikiDefinition update(DataWikiDefinition dataWiki, long expectedRevision) {
        String sql = "UPDATE datawiki_definition SET name = ?, description = ?, resources_json = ?, "
                + "updated_at = ?, revision = ? WHERE id = ? AND revision = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, dataWiki.getName());
            statement.setString(2, dataWiki.getDescription());
            statement.setString(3, JSON.toJSONString(dataWiki.getResources()));
            statement.setLong(4, dataWiki.getGmtModified().getTime());
            statement.setLong(5, dataWiki.getRevision());
            statement.setString(6, dataWiki.getId());
            statement.setLong(7, expectedRevision);
            if (statement.executeUpdate() != 1) throw new ConcurrentModificationException("DataWiki revision has changed: " + dataWiki.getId());
            return get(dataWiki.getId());
        } catch (SQLException exception) {
            throw failure("update DataWiki", exception);
        }
    }

    @Override
    public void delete(String id, long expectedRevision) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM datawiki_definition WHERE id = ? AND revision = ?")) {
            statement.setString(1, id);
            statement.setLong(2, expectedRevision);
            if (statement.executeUpdate() != 1) throw new ConcurrentModificationException("DataWiki revision has changed: " + id);
        } catch (SQLException exception) {
            throw failure("delete DataWiki", exception);
        }
    }

    private static void bind(PreparedStatement statement, DataWikiDefinition dataWiki) throws SQLException {
        statement.setString(1, dataWiki.getId());
        statement.setString(2, dataWiki.getName());
        statement.setString(3, dataWiki.getDescription());
        statement.setString(4, JSON.toJSONString(dataWiki.getResources()));
        if (dataWiki.getCreatedBy() == null) statement.setNull(5, Types.BIGINT); else statement.setLong(5, dataWiki.getCreatedBy());
        statement.setLong(6, dataWiki.getGmtCreate().getTime());
        statement.setLong(7, dataWiki.getGmtModified().getTime());
        statement.setLong(8, dataWiki.getRevision());
    }

    private static DataWikiDefinition read(ResultSet resultSet) throws SQLException {
        DataWikiDefinition dataWiki = new DataWikiDefinition();
        dataWiki.setId(resultSet.getString("id"));
        dataWiki.setName(resultSet.getString("name"));
        dataWiki.setDescription(resultSet.getString("description"));
        List<DataWikiResource> resources = JSON.parseObject(resultSet.getString("resources_json"),
                new TypeReference<List<DataWikiResource>>() { });
        dataWiki.setResources(resources == null ? new ArrayList<>() : resources);
        long owner = resultSet.getLong("created_by");
        dataWiki.setCreatedBy(resultSet.wasNull() ? null : owner);
        dataWiki.setGmtCreate(new Date(resultSet.getLong("created_at")));
        dataWiki.setGmtModified(new Date(resultSet.getLong("updated_at")));
        dataWiki.setRevision(resultSet.getLong("revision"));
        return dataWiki;
    }

    private static DataSource defaultDataSource() {
        try {
            Path database = Path.of(ConfigUtils.getEnvBasePath(), "storage", "datawiki", "chat2db-datawiki")
                    .toAbsolutePath().normalize();
            Files.createDirectories(database.getParent());
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:file:" + database + ";DB_CLOSE_ON_EXIT=FALSE;DB_CLOSE_DELAY=-1");
            dataSource.setUser("sa");
            dataSource.setPassword("");
            return dataSource;
        } catch (Exception exception) {
            throw new IllegalStateException("failed to initialize DataWiki database", exception);
        }
    }

    private static IllegalStateException failure(String operation, SQLException exception) {
        return new IllegalStateException("failed to " + operation + " in DataWiki store", exception);
    }
}
