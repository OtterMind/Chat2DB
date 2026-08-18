package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.tools.exception.BusinessException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

final class AgentTaskScheduleCron {

    static final String INVALID_CRON = "agent.schedule.invalidCron";
    static final String INVALID_TIMEZONE = "agent.schedule.invalidTimezone";

    private AgentTaskScheduleCron() {
    }

    static List<Date> next(String expression, String timezone, Date after, int count) {
        if (StringUtils.isBlank(expression)) {
            throw new BusinessException(INVALID_CRON);
        }
        String normalized = expression.trim().replaceAll("\\s+", " ");
        if (normalized.split(" ").length != 5 || hasUnsupportedExtension(normalized)) {
            throw new BusinessException(INVALID_CRON);
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(StringUtils.defaultIfBlank(timezone, "UTC"));
        } catch (RuntimeException exception) {
            throw new BusinessException(INVALID_TIMEZONE, new Object[]{timezone}, exception);
        }
        CronExpression cron;
        try {
            cron = CronExpression.parse("0 " + normalized);
        } catch (RuntimeException exception) {
            throw new BusinessException(INVALID_CRON, new Object[]{expression}, exception);
        }
        ZonedDateTime cursor = Instant.ofEpochMilli(after.getTime()).atZone(zone);
        List<Date> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            ZonedDateTime next = cron.next(cursor);
            if (next == null) break;
            result.add(Date.from(next.toInstant()));
            cursor = next;
        }
        return result;
    }

    private static boolean hasUnsupportedExtension(String expression) {
        if (expression.contains("#") || expression.contains("?")) return true;
        for (String field : expression.toUpperCase().split(" ")) {
            for (String part : field.split(",")) {
                if (part.equals("L") || part.equals("LW") || part.startsWith("L-")
                        || part.matches("\\d+[LW]")) return true;
            }
        }
        return false;
    }
}
