package ai.chat2db.community.start.ai.subscription.appserver.dto;

import java.util.List;
import java.util.Locale;

/**
 * Availability snapshot entry from model/list. Contains no secrets.
 */
public final class AppServerModelDescriptor {

    private final String id;
    private final String displayName;
    private final boolean hidden;
    private final boolean isDefault;
    private final List<String> inputModalities;
    private final List<String> supportedReasoningEfforts;
    private final String defaultReasoningEffort;
    /** Catalog {@code tool_mode} when present (e.g. {@code code_mode_only}). */
    private final String toolMode;

    public AppServerModelDescriptor(String id, String displayName, boolean hidden, boolean isDefault,
                                    List<String> inputModalities) {
        this(id, displayName, hidden, isDefault, inputModalities, List.of(), null, null);
    }

    public AppServerModelDescriptor(String id, String displayName, boolean hidden, boolean isDefault,
                                    List<String> inputModalities, List<String> supportedReasoningEfforts,
                                    String defaultReasoningEffort) {
        this(id, displayName, hidden, isDefault, inputModalities, supportedReasoningEfforts,
                defaultReasoningEffort, null);
    }

    public AppServerModelDescriptor(String id, String displayName, boolean hidden, boolean isDefault,
                                    List<String> inputModalities, List<String> supportedReasoningEfforts,
                                    String defaultReasoningEffort, String toolMode) {
        this.id = id;
        this.displayName = displayName;
        this.hidden = hidden;
        this.isDefault = isDefault;
        // Official older catalogs omit this field and are text-compatible by definition.
        this.inputModalities = inputModalities == null || inputModalities.isEmpty()
                ? List.of("text", "image") : List.copyOf(inputModalities);
        this.supportedReasoningEfforts = supportedReasoningEfforts == null
                ? List.of() : List.copyOf(supportedReasoningEfforts);
        this.defaultReasoningEffort = defaultReasoningEffort;
        this.toolMode = toolMode == null || toolMode.isBlank() ? null : toolMode.trim();
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public boolean hidden() {
        return hidden;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public List<String> inputModalities() {
        return inputModalities;
    }

    public List<String> supportedReasoningEfforts() {
        return supportedReasoningEfforts;
    }

    public String defaultReasoningEffort() {
        return defaultReasoningEffort;
    }

    public String toolMode() {
        return toolMode;
    }

    /** True when the catalog forces tools only through CodeMode {@code exec} nested MCP. */
    public boolean codeModeOnly() {
        if (toolMode != null && "code_mode_only".equalsIgnoreCase(toolMode)) {
            return true;
        }
        // Known live GPT-5.6 catalog ids omit reliable discovery on older caches; deny by id too.
        if (id == null || id.isBlank()) {
            return false;
        }
        String normalized = id.toLowerCase(Locale.ROOT);
        return normalized.equals("gpt-5.6-luna")
                || normalized.equals("gpt-5.6-sol")
                || normalized.equals("gpt-5.6-terra")
                || normalized.startsWith("gpt-5.6-luna-")
                || normalized.startsWith("gpt-5.6-sol-")
                || normalized.startsWith("gpt-5.6-terra-");
    }
}
