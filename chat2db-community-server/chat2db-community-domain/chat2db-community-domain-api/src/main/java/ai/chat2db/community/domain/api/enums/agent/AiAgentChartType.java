package ai.chat2db.community.domain.api.enums.agent;

import java.util.Arrays;
import java.util.List;

public enum AiAgentChartType {
    COLUMN("Column"), BAR("Bar"), LINE("Line"), AREA_LINE("AreaLine"),
    PIE("Pie"), RING_PIE("RingPie"), ROSE_PIE("RosePie"),
    FUNNEL("Funnel"), SCATTER("Scatter"), STATISTICS("Statistics"), COMBO("Combo");

    private final String code;

    AiAgentChartType(String code) { this.code = code; }

    public String getCode() { return code; }

    public static AiAgentChartType from(String code) {
        return Arrays.stream(values()).filter(type -> type.code.equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported chartType: " + code));
    }

    public static List<String> codes() { return Arrays.stream(values()).map(AiAgentChartType::getCode).toList(); }
}
