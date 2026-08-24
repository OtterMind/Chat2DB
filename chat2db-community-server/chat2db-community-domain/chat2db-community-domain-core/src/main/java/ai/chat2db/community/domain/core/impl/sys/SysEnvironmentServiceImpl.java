package ai.chat2db.community.domain.core.impl.sys;

import ai.chat2db.community.domain.api.config.Environment;
import ai.chat2db.community.domain.api.service.sys.ISysEnvironmentService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SysEnvironmentServiceImpl implements ISysEnvironmentService {

    private static final Environment TEST = Environment.builder()
            .id(2L)
            .name("TEST")
            .shortName("Test Environment")
            .color("GREEN")
            .build();

    private static final Environment RELEASE = Environment.builder()
            .id(1L)
            .name("RELEASE")
            .shortName("Release Environment")
            .color("RED")
            .build();

    private static final Environment DEV = Environment.builder()
            .id(3L)
            .name("DEV")
            .shortName("Development Environment")
            .build();

    @Override
    public List<Environment> listAll() {
        return List.of(TEST, DEV, RELEASE);
    }
}
