package ai.chat2db.server.web.api.controller.rdb;

import ai.chat2db.server.domain.api.param.CreateVirtualFKParam;
import ai.chat2db.server.domain.api.param.UpdateVirtualFKParam;
import ai.chat2db.server.domain.api.service.ForeignKeySyncService;
import ai.chat2db.server.domain.api.service.TableService;
import ai.chat2db.server.tools.base.wrapper.result.ActionResult;
import ai.chat2db.server.tools.base.wrapper.result.DataResult;
import ai.chat2db.server.tools.base.wrapper.result.ListResult;
import ai.chat2db.server.web.api.controller.rdb.request.CreateVirtualFKRequest;
import ai.chat2db.server.web.api.controller.rdb.request.DeleteFKByNameRequest;
import ai.chat2db.server.web.api.controller.rdb.request.DeleteFKRequest;
import ai.chat2db.server.web.api.controller.rdb.request.ForeignKeyListRequest;
import ai.chat2db.server.web.api.controller.rdb.request.ForeignKeySyncRequest;
import ai.chat2db.server.web.api.controller.rdb.request.UpdateVirtualFKRequest;
import ai.chat2db.server.web.api.controller.rdb.vo.DeleteFKResult;
import ai.chat2db.server.web.api.controller.rdb.vo.ForeignKeyVO;
import ai.chat2db.server.web.api.controller.rdb.vo.SyncResult;
import ai.chat2db.server.web.api.aspect.ConnectionInfoAspect;
import ai.chat2db.spi.model.ForeignKey;
import ai.chat2db.spi.model.VirtualForeignKey;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@ConnectionInfoAspect
@RequestMapping("/api/rdb/fk")
@RestController
public class ForeignKeyController {

    @Autowired
    private ForeignKeySyncService foreignKeySyncService;


    @PostMapping("/sync")
    public DataResult<SyncResult> sync(@Valid @RequestBody ForeignKeySyncRequest request) {
        ForeignKeySyncService.SyncResult result = foreignKeySyncService.syncForeignKeys(
                request.getDataSourceId(),
                request.getDatabaseName(),
                request.getSchemaName(),
                request.getTableName()
        );
        return DataResult.of(SyncResult.builder()
                .added(result.getAdded())
                .deleted(result.getDeleted())
                .unchanged(result.getUnchanged())
                .build());
    }

    @GetMapping("/list")
    public ListResult<ForeignKeyVO> list(@Valid ForeignKeyListRequest request) {
        List<ForeignKey> fks = foreignKeySyncService.listAllForeignKeys(
                request.getDataSourceId(),
                request.getDatabaseName(),
                request.getSchemaName(),
                request.getTableName()
        );
        List<ForeignKeyVO> voList = fks.stream()
                .map(fk -> ForeignKeyVO.builder()
                        .id(fk.getId())
                        .name(fk.getName())
                        .tableName(fk.getTableName())
                        .columnName(fk.getColumn())
                        .referencedTable(fk.getReferencedTable())
                        .referencedColumnName(fk.getReferencedColumn())
                        .comment(fk.getComment())
                        .updateRule(fk.getUpdateRule())
                        .deleteRule(fk.getDeleteRule())
                        .sourceType(fk instanceof VirtualForeignKey ? "VIRTUAL" : "REAL")
                        .editable(fk instanceof VirtualForeignKey)
                        .build())
                .collect(Collectors.toList());
        return ListResult.of(voList);
    }

    @PostMapping("/virtual/create")
    public DataResult<VirtualForeignKey> createVirtual(@Valid @RequestBody CreateVirtualFKRequest request) {
        CreateVirtualFKParam param = CreateVirtualFKParam.builder()
                .dataSourceId(request.getDataSourceId())
                .databaseName(request.getDatabaseName())
                .schemaName(request.getSchemaName())
                .tableName(request.getTableName())
                .columnName(request.getColumnName())
                .referencedTable(request.getReferencedTable())
                .referencedColumnName(request.getReferencedColumnName())
                .comment(request.getComment())
                .build();
        return DataResult.of(foreignKeySyncService.createVirtualFK(param));
    }

    @PostMapping("/virtual/update")
    public DataResult<VirtualForeignKey> updateVirtual(@Valid @RequestBody UpdateVirtualFKRequest request) {
        UpdateVirtualFKParam param = UpdateVirtualFKParam.builder()
                .id(request.getId())
                .dataSourceId(request.getDataSourceId())
                .databaseName(request.getDatabaseName())
                .schemaName(request.getSchemaName())
                .comment(request.getComment())
                .tableName(request.getTableName())
                .columnName(request.getColumnName())
                .referencedTable(request.getReferencedTable())
                .referencedColumnName(request.getReferencedColumnName())
                .vkName(request.getVkName())
                .build();
        return DataResult.of(foreignKeySyncService.updateVirtualFK(param));
    }

    @PostMapping("/delete")
    public DataResult<DeleteFKResult> delete(@Valid @RequestBody DeleteFKRequest request) {
        if ("VIRTUAL".equals(request.getSourceType())) {
            foreignKeySyncService.deleteVirtualFK(request.getId());
            return DataResult.of(DeleteFKResult.builder().executedDDL(null).build());
        } else {
            String ddl = foreignKeySyncService.deleteRealFK(request.getId());
            return DataResult.of(DeleteFKResult.builder().executedDDL(ddl).build());
        }
    }

    @PostMapping("/delete_by_name")
    public ActionResult deleteByName(@Valid @RequestBody DeleteFKByNameRequest request) {
        List<VirtualForeignKey> virtualFKs = foreignKeySyncService.queryVirtualForeignKeys(
                request.getDataSourceId(),
                request.getDatabaseName(),
                request.getSchemaName(),
                request.getTableName()
        );
        for (VirtualForeignKey vk : virtualFKs) {
            if (vk.getName() != null && vk.getName().equals(request.getKeyName())) {
                foreignKeySyncService.deleteVirtualFK(vk.getId());
                return ActionResult.isSuccess();
            }
        }
        return ActionResult.fail("VIRTUAL_FK_NOT_FOUND", "虚拟外键不存在", "");
    }
}
