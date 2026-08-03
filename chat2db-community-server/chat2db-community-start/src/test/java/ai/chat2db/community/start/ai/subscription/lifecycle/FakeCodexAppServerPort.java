package ai.chat2db.community.start.ai.subscription.lifecycle;

import ai.chat2db.community.start.ai.subscription.appserver.AppServerDisabledReason;
import ai.chat2db.community.start.ai.subscription.appserver.AppServerEventListener;
import ai.chat2db.community.start.ai.subscription.appserver.CodexAppServerPort;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerAccountView;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerLoginStartResult;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerModelDescriptor;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerThreadView;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerTurnView;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process fake for lifecycle tests. Never returns real provider tokens.
 */
final class FakeCodexAppServerPort implements CodexAppServerPort {

    private boolean enabled = true;
    private boolean authenticated;
    private String loginAuthUrl = "https://chatgpt.com/oauth?state=test";
    private String loginTypeUsed;
    private final AtomicInteger logoutCalls = new AtomicInteger();
    private final AtomicInteger cancelCalls = new AtomicInteger();
    private final List<AppServerModelDescriptor> models = new ArrayList<>();
    private RuntimeException listModelsFailure;
    private RuntimeException logoutFailure;
    private final AtomicBoolean readUnauthenticatedAfterLogout = new AtomicBoolean(true);

    FakeCodexAppServerPort() {
        models.add(new AppServerModelDescriptor("gpt-5.4", "GPT-5.4", false, true, List.of("text", "image")));
        models.add(new AppServerModelDescriptor("hidden-model", "Hidden", true, false, List.of("text")));
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    void setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
    }

    void setLoginAuthUrl(String loginAuthUrl) {
        this.loginAuthUrl = loginAuthUrl;
    }

    void setListModelsFailure(RuntimeException listModelsFailure) {
        this.listModelsFailure = listModelsFailure;
    }

    void setLogoutFailure(RuntimeException logoutFailure) {
        this.logoutFailure = logoutFailure;
    }

    void setReadUnauthenticatedAfterLogout(boolean value) {
        this.readUnauthenticatedAfterLogout.set(value);
    }

    void setModels(List<AppServerModelDescriptor> next) {
        models.clear();
        models.addAll(next);
    }

    int logoutCalls() {
        return logoutCalls.get();
    }

    int cancelCalls() {
        return cancelCalls.get();
    }

    String loginTypeUsed() {
        return loginTypeUsed;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public Optional<AppServerDisabledReason> disabledReason() {
        return enabled ? Optional.empty() : Optional.of(AppServerDisabledReason.FEATURE_DISABLED_BY_DEFAULT);
    }

    @Override
    public void start() {
    }

    @Override
    public void shutdown() {
    }

    @Override
    public AppServerAccountView readAccount(boolean refreshToken) {
        if (!authenticated) {
            return new AppServerAccountView(false, null, null, null);
        }
        return new AppServerAccountView(true, "chatgpt", "u***@example.com", "plus");
    }

    @Override
    public AppServerLoginStartResult startChatGptLogin(String type) {
        this.loginTypeUsed = type;
        if ("chatgpt".equals(type)) {
            return new AppServerLoginStartResult("chatgpt", "login-1", loginAuthUrl, null, null);
        }
        return new AppServerLoginStartResult(
                "chatgptDeviceCode",
                "login-device-1",
                null,
                "https://auth.openai.com/codex/device",
                "ABCD-1234");
    }

    @Override
    public void cancelLogin(String loginId) {
        cancelCalls.incrementAndGet();
    }

    @Override
    public void logout() {
        logoutCalls.incrementAndGet();
        if (logoutFailure != null) {
            throw logoutFailure;
        }
        if (readUnauthenticatedAfterLogout.get()) {
            authenticated = false;
        }
    }

    @Override
    public List<AppServerModelDescriptor> listModels(boolean includeHidden) {
        if (listModelsFailure != null) {
            throw listModelsFailure;
        }
        List<AppServerModelDescriptor> copy = new ArrayList<>();
        for (AppServerModelDescriptor model : models) {
            if (includeHidden || !model.hidden()) {
                copy.add(model);
            }
        }
        return copy;
    }

    @Override
    public AppServerThreadView startThread(String model) {
        throw new UnsupportedOperationException("out of T3 scope");
    }

    @Override
    public AppServerThreadView resumeThread(String threadId) {
        throw new UnsupportedOperationException("out of T3 scope");
    }

    @Override
    public AppServerThreadView readThread(String threadId, boolean includeTurns) {
        throw new UnsupportedOperationException("out of T3 scope");
    }

    @Override
    public AppServerTurnView startTurn(String threadId, String textInput) {
        throw new UnsupportedOperationException("out of T3 scope");
    }

    @Override
    public void interruptTurn(String threadId, String turnId) {
        throw new UnsupportedOperationException("out of T3 scope");
    }

    @Override
    public void addEventListener(AppServerEventListener listener) {
    }

    @Override
    public void removeEventListener(AppServerEventListener listener) {
    }
}
