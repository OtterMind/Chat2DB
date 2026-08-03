package ai.chat2db.community.start.ai.subscription.appserver;

import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerAccountView;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerLoginStartResult;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerModelDescriptor;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerThreadView;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerTurnView;

import java.util.List;
import java.util.Optional;

/**
 * Capability-gated internal port for ChatGPT app-server operations.
 * Disabled by default until packaging, Keyring, binary, and protocol gates pass.
 */
public interface CodexAppServerPort {

    boolean isEnabled();

    Optional<AppServerDisabledReason> disabledReason();

    void start();

    void shutdown();

    AppServerAccountView readAccount(boolean refreshToken);

    /**
     * Starts ChatGPT browser or device-code login only. API-key login is intentionally unsupported.
     */
    AppServerLoginStartResult startChatGptLogin(String type);

    void cancelLogin(String loginId);

    void logout();

    List<AppServerModelDescriptor> listModels(boolean includeHidden);

    /** Starts an ephemeral thread in the supervisor-owned empty work directory. */
    AppServerThreadView startThread(String model);

    AppServerThreadView resumeThread(String threadId);

    AppServerThreadView readThread(String threadId, boolean includeTurns);

    AppServerTurnView startTurn(String threadId, String textInput);

    /** Starts a turn with a provider-advertised reasoning effort when supplied. */
    default AppServerTurnView startTurn(String threadId, String textInput, String reasoningEffort) {
        return startTurn(threadId, textInput);
    }

    void interruptTurn(String threadId, String turnId);

    void addEventListener(AppServerEventListener listener);

    void removeEventListener(AppServerEventListener listener);
}
