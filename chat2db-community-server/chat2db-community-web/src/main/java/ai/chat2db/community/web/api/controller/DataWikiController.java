package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.datawiki.DataWikiDefinition;
import ai.chat2db.community.domain.api.model.datawiki.DataWikiDocumentBundle;
import ai.chat2db.community.domain.api.model.request.datawiki.DataWikiCreateRequest;
import ai.chat2db.community.domain.api.model.request.datawiki.DataWikiUpdateRequest;
import ai.chat2db.community.domain.api.service.datawiki.IDataWikiService;
import ai.chat2db.community.domain.api.service.sys.IIdentityService;
import ai.chat2db.community.tools.wrapper.result.ActionResult;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.wrapper.result.ListResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/api/data-wikis")
public class DataWikiController {

    private final IDataWikiService dataWikiService;
    private final IIdentityService identityService;

    public DataWikiController(IDataWikiService dataWikiService, IIdentityService identityService) {
        this.dataWikiService = dataWikiService;
        this.identityService = identityService;
    }

    @GetMapping
    public ListResult<DataWikiDefinition> list() {
        Long userId = identityService.currentUserId();
        return ListResult.of(dataWikiService.list().stream()
                .filter(item -> Objects.equals(item.getCreatedBy(), userId)).toList());
    }

    @PostMapping
    public DataResult<DataWikiDefinition> create(@RequestBody DataWikiCreateRequest request) {
        request.setCreatedBy(identityService.currentUserId());
        return DataResult.of(dataWikiService.create(request));
    }

    @GetMapping("/{id}")
    public DataResult<DataWikiDefinition> get(@PathVariable String id) {
        DataWikiDefinition dataWiki = dataWikiService.get(id);
        requireOwner(dataWiki);
        return DataResult.of(dataWiki);
    }

    @PostMapping("/{id}")
    public DataResult<DataWikiDefinition> update(@PathVariable String id, @RequestBody DataWikiUpdateRequest request) {
        requireOwner(dataWikiService.get(id));
        request.setId(id);
        return DataResult.of(dataWikiService.update(request));
    }

    @DeleteMapping("/{id}")
    public ActionResult delete(@PathVariable String id, @RequestParam long expectedRevision) {
        requireOwner(dataWikiService.get(id));
        dataWikiService.delete(id, expectedRevision);
        return ActionResult.isSuccess();
    }

    @GetMapping("/{id}/markdown")
    public DataResult<String> markdown(@PathVariable String id) {
        requireOwner(dataWikiService.get(id));
        return DataResult.of(dataWikiService.renderMarkdown(id));
    }

    @GetMapping("/{id}/documents")
    public DataResult<DataWikiDocumentBundle> documents(@PathVariable String id) {
        requireOwner(dataWikiService.get(id));
        return DataResult.of(dataWikiService.documents(id));
    }

    @GetMapping("/{id}/documents/content")
    public DataResult<String> documentContent(@PathVariable String id, @RequestParam String path) {
        requireOwner(dataWikiService.get(id));
        return DataResult.of(dataWikiService.readDocument(id, path));
    }

    private void requireOwner(DataWikiDefinition dataWiki) {
        if (!Objects.equals(dataWiki.getCreatedBy(), identityService.currentUserId())) {
            throw new IllegalArgumentException("DataWiki does not belong to current user");
        }
    }
}
