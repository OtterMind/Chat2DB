package ai.chat2db.community.domain.core.impl.sys;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import ai.chat2db.community.domain.api.config.Environment;
import org.junit.jupiter.api.Test;

class SysEnvironmentServiceImplTest {

    @Test
    void listAllReturnsTestDevReleaseInDisplayOrder() {
        List<Environment> environments = new SysEnvironmentServiceImpl().listAll();

        assertEquals(List.of("TEST", "DEV", "RELEASE"),
                environments.stream().map(Environment::getName).toList());
        assertEquals(List.of(2L, 3L, 1L),
                environments.stream().map(Environment::getId).toList());
    }
}
