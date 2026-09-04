package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.metadata.TablePartition;
import ai.chat2db.community.domain.api.service.db.IDbPartitionService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Exposes MySQL table partition inspection and maintenance (MYSQL-OBJ-009).
 */
@ConnectionInfoAspect
@RequestMapping("/api/rdb/partition")
@RestController
public class DbPartitionController {

    @Autowired
    private IDbPartitionService partitionService;

    @PostMapping("/list")
    public DataResult<List<TablePartition>> list(@Valid @RequestBody PartitionListRequest request) {
        return DataResult.of(partitionService.list(request.getDatabaseName(), request.getTableName()));
    }

    @RequestMapping(value = "/truncate_sql", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<String> truncateSql(@Valid @RequestBody PartitionRequest request) {
        return DataResult.of(partitionService.truncatePartitionSql(
                request.getDatabaseName(), request.getTableName(), request.getPartitionName()));
    }

    @RequestMapping(value = "/drop_sql", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<String> dropSql(@Valid @RequestBody PartitionRequest request) {
        return DataResult.of(partitionService.dropPartitionSql(
                request.getDatabaseName(), request.getTableName(), request.getPartitionName()));
    }

    @RequestMapping(value = "/add_sql", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<String> addSql(@Valid @RequestBody AddRequest request) {
        return DataResult.of(partitionService.addPartitionSql(
                request.getDatabaseName(), request.getTableName(), request.getPartitionName(),
                request.getPartitionDefinition(), request.getCount()));
    }

    @RequestMapping(value = "/reorganize_sql", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<String> reorganizeSql(@Valid @RequestBody ReorganizeRequest request) {
        return DataResult.of(partitionService.reorganizePartitionSql(
                request.getDatabaseName(), request.getTableName(), request.getPartitionName(),
                request.getPartitionDefinitions()));
    }

    @RequestMapping(value = "/coalesce_sql", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<String> coalesceSql(@Valid @RequestBody CoalesceRequest request) {
        return DataResult.of(partitionService.coalescePartitionSql(
                request.getDatabaseName(), request.getTableName(), request.getCount()));
    }

    @RequestMapping(value = "/maintain_sql", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<String> maintainSql(@Valid @RequestBody MaintainRequest request) {
        return DataResult.of(partitionService.maintainPartitionSql(
                request.getDatabaseName(), request.getTableName(), request.getOperation(), request.getPartitionName()));
    }

    @Data
    public static class PartitionListRequest extends DataSourceBaseRequest {

        @NotBlank
        private String tableName;
    }

    @Data
    public static class PartitionRequest extends PartitionListRequest {


        @NotBlank
        private String partitionName;
    }

    @Data
    public static class CoalesceRequest extends PartitionListRequest {

        @NotNull
        private Integer count;
    }

    @Data
    public static class AddRequest extends PartitionListRequest {

        private String partitionName;

        private String partitionDefinition;

        private Integer count;
    }

    @Data
    public static class ReorganizeRequest extends PartitionRequest {

        @NotBlank
        private String partitionDefinitions;
    }

    @Data
    public static class MaintainRequest extends PartitionListRequest {

        @NotBlank
        private String operation;

        private String partitionName;
    }
}
