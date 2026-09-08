package ai.chat2db.community.domain.api.model.agent;

import java.util.Objects;
import java.util.regex.Pattern;

public record AgentRuntimeId(String value) {

    private static final Pattern VALID_VALUE = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    public AgentRuntimeId {
        Objects.requireNonNull(value, "value");
        if (!VALID_VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid agent runtime id: " + value);
        }
    }
}
