package ai.chat2db.community.domain.api.service.ai;

public interface IAiSystemPromptService {

    String defaultSystemPrompt(boolean databaseToolsAvailable);
}
