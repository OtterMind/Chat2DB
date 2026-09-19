package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.agent.context.AiAgentRunContext;
import ai.chat2db.community.domain.api.service.agent.IAiAgentPromptService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import freemarker.core.PlainTextOutputFormat;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class AiAgentPromptServiceImpl implements IAiAgentPromptService {
    private final Template system;
    private final Template user;
    private final ObjectMapper json = new ObjectMapper();

    public AiAgentPromptServiceImpl() {
        Configuration configuration = new Configuration(Configuration.VERSION_2_3_34);
        configuration.setClassForTemplateLoading(AiAgentPromptServiceImpl.class, "/prompts/agent");
        configuration.setDefaultEncoding(StandardCharsets.UTF_8.name());
        configuration.setLocale(Locale.ROOT);
        configuration.setLocalizedLookup(false);
        configuration.setOutputFormat(PlainTextOutputFormat.INSTANCE);
        configuration.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        configuration.setLogTemplateExceptions(false);
        configuration.setWrapUncheckedExceptions(true);
        try {
            system = configuration.getTemplate("system.ftl");
            user = configuration.getTemplate("user.ftl");
        } catch (IOException error) {
            throw new IllegalStateException("Cannot load Agent prompt templates", error);
        }
    }

    @Override
    public String systemPrompt() { return render(system, Map.of()); }

    @Override
    public String userPrompt(String userMessage, AiAgentRunContext context) {
        Objects.requireNonNull(userMessage, "userMessage");
        Objects.requireNonNull(context, "context");
        try {
            return render(user, Map.of("userMessage", userMessage,
                    "contextJson", json.writerWithDefaultPrettyPrinter().writeValueAsString(context)));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot serialize Agent prompt context", error);
        }
    }

    private String render(Template template, Map<String, Object> variables) {
        StringWriter output = new StringWriter();
        try {
            template.process(variables, output);
            return output.toString();
        } catch (IOException | TemplateException error) {
            throw new IllegalStateException("Cannot render Agent prompt template " + template.getName(), error);
        }
    }
}
