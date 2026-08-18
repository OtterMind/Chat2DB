package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentTaskScheduleCronTest {

    @Test
    void computesFiveFieldCronInConfiguredTimezone() {
        Date after = Date.from(Instant.parse("2026-08-14T02:00:00Z")); // Friday 10:00 Shanghai

        List<Date> next = AgentTaskScheduleCron.next(
                "0 9 * * 1-5", "Asia/Shanghai", after, 3);

        assertEquals(List.of(
                Date.from(Instant.parse("2026-08-17T01:00:00Z")),
                Date.from(Instant.parse("2026-08-18T01:00:00Z")),
                Date.from(Instant.parse("2026-08-19T01:00:00Z"))), next);
    }

    @Test
    void observesDstWhenComputingOccurrences() {
        Date after = Date.from(Instant.parse("2026-03-07T12:00:00Z"));

        List<Date> next = AgentTaskScheduleCron.next(
                "0 9 * * *", "America/New_York", after, 2);

        assertEquals(Date.from(Instant.parse("2026-03-07T14:00:00Z")), next.get(0));
        assertEquals(Date.from(Instant.parse("2026-03-08T13:00:00Z")), next.get(1));
    }

    @Test
    void rejectsUnsupportedSixFieldCronAndInvalidTimezone() {
        Date now = Date.from(Instant.parse("2026-08-17T00:00:00Z"));
        BusinessException cron = assertThrows(BusinessException.class,
                () -> AgentTaskScheduleCron.next("0 0 9 * * *", "UTC", now, 1));
        BusinessException timezone = assertThrows(BusinessException.class,
                () -> AgentTaskScheduleCron.next("0 9 * * *", "Invalid/Zone", now, 1));
        BusinessException extension = assertThrows(BusinessException.class,
                () -> AgentTaskScheduleCron.next("0 9 L * *", "UTC", now, 1));
        assertEquals(AgentTaskScheduleCron.INVALID_CRON, cron.getCode());
        assertEquals(AgentTaskScheduleCron.INVALID_TIMEZONE, timezone.getCode());
        assertEquals(AgentTaskScheduleCron.INVALID_CRON, extension.getCode());
    }
}
