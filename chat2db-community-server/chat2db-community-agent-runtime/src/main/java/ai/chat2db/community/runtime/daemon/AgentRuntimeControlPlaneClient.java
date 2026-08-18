package ai.chat2db.community.runtime.daemon;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeEventAccepted;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeInstance;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeLeaseStatus;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeRunClaim;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeRunTerminalResult;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeApprovalResult;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeArtifactResult;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeEventRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeHeartbeatRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeInstanceRegisterRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeLeaseRenewRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunCancelAckRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunClaimRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunCompleteRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunFailRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunStartedRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunSuspendRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeApprovalRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeApprovalAckRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeArtifactUploadRequest;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class AgentRuntimeControlPlaneClient {

    private static final String API_PREFIX = "/api/agent/runtime/daemon";
    private static final String LEASE_HEADER = "X-Chat2DB-Agent-Run-Lease";

    private final URI baseUri;
    private final String daemonToken;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public AgentRuntimeControlPlaneClient(URI baseUri, String daemonToken) {
        this(baseUri, daemonToken, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build(), new ObjectMapper());
    }

    AgentRuntimeControlPlaneClient(URI baseUri, String daemonToken,
                                   HttpClient httpClient, ObjectMapper mapper) {
        validateBaseUri(baseUri);
        if (StringUtils.isBlank(daemonToken)) {
            throw new IllegalArgumentException("Runtime Daemon token is required");
        }
        this.baseUri = baseUri;
        this.daemonToken = daemonToken;
        this.httpClient = httpClient;
        this.mapper = mapper;
    }

    public AgentRuntimeInstance register(AgentRuntimeInstanceRegisterRequest request) {
        return post("/instances/register", request, null, AgentRuntimeInstance.class);
    }

    public AgentRuntimeInstance heartbeat(String instanceId, AgentRuntimeHeartbeatRequest request) {
        return post("/instances/" + segment(instanceId) + "/heartbeat",
                request, null, AgentRuntimeInstance.class);
    }

    public AgentRuntimeRunClaim claim(String instanceId, AgentRuntimeRunClaimRequest request) {
        return post("/instances/" + segment(instanceId) + "/runs/claim",
                request, null, AgentRuntimeRunClaim.class);
    }

    public AgentRuntimeLeaseStatus started(String runId, String leaseToken,
                                           AgentRuntimeRunStartedRequest request) {
        return post("/runs/" + segment(runId) + "/started",
                request, leaseToken, AgentRuntimeLeaseStatus.class);
    }

    public AgentRuntimeLeaseStatus renew(String runId, String leaseToken,
                                         AgentRuntimeLeaseRenewRequest request) {
        return post("/runs/" + segment(runId) + "/lease/renew",
                request, leaseToken, AgentRuntimeLeaseStatus.class);
    }

    public AgentRuntimeLeaseStatus suspendForSqlApproval(String runId, String leaseToken,
                                                          AgentRuntimeRunSuspendRequest request) {
        return post("/runs/" + segment(runId) + "/suspend-for-sql-approval",
                request, leaseToken, AgentRuntimeLeaseStatus.class);
    }

    public AgentRuntimeEventAccepted event(String runId, String leaseToken,
                                           AgentRuntimeEventRequest request) {
        return post("/runs/" + segment(runId) + "/events",
                request, leaseToken, AgentRuntimeEventAccepted.class);
    }

    public AgentRuntimeArtifactResult uploadArtifact(String runId, String leaseToken,
                                                     AgentRuntimeArtifactUploadRequest request) {
        return post("/runs/" + segment(runId) + "/artifacts",
                request, leaseToken, AgentRuntimeArtifactResult.class);
    }

    public AgentRuntimeApprovalResult requestApproval(String runId, String leaseToken,
                                                      AgentRuntimeApprovalRequest request) {
        return post("/runs/" + segment(runId) + "/approvals/request",
                request, leaseToken, AgentRuntimeApprovalResult.class);
    }

    public AgentRuntimeApprovalResult approvalStatus(String runId, String leaseToken,
                                                     AgentRuntimeApprovalAckRequest request) {
        return post("/runs/" + segment(runId) + "/approvals/status",
                request, leaseToken, AgentRuntimeApprovalResult.class);
    }

    public AgentRuntimeApprovalResult acknowledgeApproval(String runId, String leaseToken,
                                                          AgentRuntimeApprovalAckRequest request) {
        return post("/runs/" + segment(runId) + "/approvals/ack",
                request, leaseToken, AgentRuntimeApprovalResult.class);
    }

    public AgentRuntimeRunTerminalResult complete(String runId, String leaseToken,
                                                   AgentRuntimeRunCompleteRequest request) {
        return post("/runs/" + segment(runId) + "/complete",
                request, leaseToken, AgentRuntimeRunTerminalResult.class);
    }

    public AgentRuntimeRunTerminalResult fail(String runId, String leaseToken,
                                               AgentRuntimeRunFailRequest request) {
        return post("/runs/" + segment(runId) + "/fail",
                request, leaseToken, AgentRuntimeRunTerminalResult.class);
    }

    public AgentRuntimeRunTerminalResult cancelAck(String runId, String leaseToken,
                                                    AgentRuntimeRunCancelAckRequest request) {
        return post("/runs/" + segment(runId) + "/cancel-ack",
                request, leaseToken, AgentRuntimeRunTerminalResult.class);
    }

    public URI resolveTaskMcpEndpoint(String path) {
        if (StringUtils.isBlank(path) || !path.startsWith("/api/agent/runtime/mcp/runs/")) {
            throw new IllegalArgumentException("Runtime MCP endpoint must use the task-scoped API path");
        }
        URI relative;
        try {
            relative = URI.create(path);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Runtime MCP endpoint path is invalid", exception);
        }
        if (relative.isAbsolute() || relative.getRawAuthority() != null || relative.getQuery() != null
                || relative.getFragment() != null || relative.getPath().contains("..")) {
            throw new IllegalArgumentException("Runtime MCP endpoint must be a safe relative API path");
        }
        URI resolved = baseUri.resolve(relative);
        if (!baseUri.getScheme().equalsIgnoreCase(resolved.getScheme())
                || !baseUri.getHost().equalsIgnoreCase(resolved.getHost())
                || effectivePort(baseUri) != effectivePort(resolved)) {
            throw new IllegalArgumentException("Runtime MCP endpoint must retain the control-plane origin");
        }
        return resolved;
    }

    private <T> T post(String path, Object body, String leaseToken, Class<T> responseType) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(API_PREFIX + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + daemonToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            if (StringUtils.isNotBlank(leaseToken)) {
                request.header(LEASE_HEADER, leaseToken);
            }
            HttpResponse<String> response = httpClient.send(
                    request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ControlPlaneException("Runtime control plane returned HTTP " + response.statusCode());
            }
            JsonNode envelope = mapper.readTree(response.body());
            if (!envelope.path("success").asBoolean(false)) {
                String code = envelope.path("errorCode").asText("common.runtimeControlError");
                String message = envelope.path("errorMessage").asText("Runtime control plane rejected request");
                throw new ControlPlaneRejectedException(code, message);
            }
            JsonNode data = envelope.get("data");
            if (data == null || data.isNull()) {
                return null;
            }
            JavaType type = mapper.getTypeFactory().constructType(responseType);
            return mapper.convertValue(data, type);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ControlPlaneException("Runtime control plane request interrupted", exception);
        } catch (IOException exception) {
            throw new ControlPlaneException("Runtime control plane request failed", exception);
        }
    }

    private String segment(String value) {
        if (StringUtils.isBlank(value) || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Runtime API identifier contains invalid characters");
        }
        return value;
    }

    private void validateBaseUri(URI uri) {
        if (uri == null || !"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Runtime control plane URL must be a plain loopback HTTP origin");
        }
        try {
            if (!InetAddress.getByName(uri.getHost()).isLoopbackAddress()) {
                throw new IllegalArgumentException("Community Runtime Daemon only connects to loopback");
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Runtime control plane host cannot be resolved", exception);
        }
    }

    private int effectivePort(URI uri) {
        return uri.getPort() < 0 ? 80 : uri.getPort();
    }
}
