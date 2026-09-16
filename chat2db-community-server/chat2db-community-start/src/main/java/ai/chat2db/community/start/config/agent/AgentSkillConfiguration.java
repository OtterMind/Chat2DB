package ai.chat2db.community.start.config.agent;

import ai.chat2db.community.domain.api.service.agent.IAiAgentSkillService;
import ai.chat2db.community.domain.core.impl.agent.AiAgentSkillServiceImpl;
import ai.chat2db.community.tools.util.ConfigUtils;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.annotation.Value;

@Configuration
public class AgentSkillConfiguration {
    @Bean
    public IAiAgentSkillService agentSkillService(
            @Value("${chat2db.agent.v2.skills.directory:${user.home}/.chat2db-skills}") String directory) {
        Path skillRoot = Path.of(directory).toAbsolutePath().normalize();
        return new AiAgentSkillServiceImpl(new ClassPathResource("/skills/catalog.json", AgentSkillConfiguration.class),
                skillRoot.resolve(".resources"), skillRoot,
                Path.of(ConfigUtils.getEnvBasePath()).resolve("storage/ai-chat-history-v2/resources/skills"));
    }
}
