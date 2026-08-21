package ai.chat2db.community.jcef.enums;

import lombok.Getter;

public enum ActionTypeEnum {

    AI_SSE_MESSAGE("ai_sse_message", "AI chat message"),
    STARTUP_COMPLETE("startup_complete", "Application started successfully"),
    UPDATE_PROGRESS("update_progress", "Update progress"),
    OPEN_FILE("open_file", "Open specified file"),
    OSS_LOGIN("oss_login", "Single sign-on"),
    WINDOW_FULL_SCREEN_CHANGED("window_full_screen_changed", "Native full screen state changed"),
    TERMINAL_OUTPUT("terminal_output", "Integrated terminal output"),
    TERMINAL_EXIT("terminal_exit", "Integrated terminal exit"),
    APP_EXIT_REQUESTED("app_exit_requested", "Application exit requested"),
    APP_EXIT_RESULT("app_exit_result", "Application exit request result")
    ;

    @Getter
    private final String name;
    @Getter
    private final String description;

    ActionTypeEnum(String name, String description) {
        this.name = name;
        this.description = description;
    }


}
