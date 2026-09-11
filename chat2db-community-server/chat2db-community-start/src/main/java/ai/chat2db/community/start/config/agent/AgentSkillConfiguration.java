package ai.chat2db.community.start.config.agent;

import ai.chat2db.community.domain.api.service.agent.IAiAgentSkillService;
import ai.chat2db.community.domain.core.impl.agent.AiAgentSkillServiceImpl;
import ai.chat2db.community.tools.util.ConfigUtils;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

@Configuration
public class AgentSkillConfiguration {
    @Bean
    public IAiAgentSkillService agentSkillService() {
        return new AiAgentSkillServiceImpl(new ClassPathResource("skills/catalog.json"),
                Path.of(ConfigUtils.getEnvBasePath()).resolve("storage/ai-chat-history-v2/resources/skills"));
    }
}
