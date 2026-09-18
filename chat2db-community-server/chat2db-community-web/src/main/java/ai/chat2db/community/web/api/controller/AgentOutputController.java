package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.agent.output.AgentOutputRead;
import ai.chat2db.community.domain.api.model.agent.output.AgentOutputSearch;
import ai.chat2db.community.domain.api.service.agent.IAgentOutputDownloadService;
import ai.chat2db.community.domain.api.service.agent.IAiAgentOutputService;
import ai.chat2db.community.domain.api.service.sys.IIdentityService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

/** V2 conversation outputs; all reads are checked against the current session owner. */
@RestController
@RequestMapping("/api/v3/ai/sessions/{sessionId}/outputs/{artifactId}")
public class AgentOutputController {
    private final IAiAgentOutputService outputs;
    private final IIdentityService identity;
    private final List<IAgentOutputDownloadService> desktopDownloads;

    public AgentOutputController(IAiAgentOutputService outputs, IIdentityService identity,
            List<IAgentOutputDownloadService> desktopDownloads) {
        this.outputs = outputs;
        this.identity = identity;
        this.desktopDownloads = desktopDownloads;
    }

    @GetMapping("/read")
    public DataResult<AgentOutputRead> read(@PathVariable String sessionId, @PathVariable String artifactId,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) Integer limit) {
        return DataResult.of(outputs.read(sessionId, identity.currentUserId(), artifactId, cursor, offset, limit));
    }

    @GetMapping("/search")
    public DataResult<AgentOutputSearch> search(@PathVariable String sessionId, @PathVariable String artifactId,
            @RequestParam String pattern, @RequestParam(defaultValue = "true") boolean literal,
            @RequestParam(defaultValue = "false") boolean ignoreCase,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        return DataResult.of(outputs.search(sessionId, identity.currentUserId(), artifactId, pattern, literal, ignoreCase, cursor, limit));
    }

    @GetMapping("/download")
    public void download(@PathVariable String sessionId, @PathVariable String artifactId,
            HttpServletResponse response) throws IOException {
        Long userId = identity.currentUserId();
        var file = outputs.reference(sessionId, userId, artifactId);
        response.setContentType("application/octet-stream");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.artifactId() + "." + file.format() + "\"");
        outputs.download(sessionId, userId, artifactId, response.getOutputStream());
    }

    @PostMapping("/download-path")
    public DataResult<String> downloadPath(@PathVariable String sessionId, @PathVariable String artifactId) {
        // Saving to a host path is a desktop capability, like the directory picker.
        AgentLocalRequestGuard.requireLocal();
        if (desktopDownloads.isEmpty()) throw new IllegalStateException("Desktop file saving is unavailable");
        return DataResult.of(desktopDownloads.get(0).save(sessionId, identity.currentUserId(), artifactId));
    }
}
