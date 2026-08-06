package ai.chat2db.community.web.api.adapter.lifecycle;

import ai.chat2db.community.domain.api.service.db.IDbConnectionContextService;
import ai.chat2db.community.domain.api.service.sys.ISysApplicationLifecycleService;
import ai.chat2db.community.tools.model.ConfigJson;
import ai.chat2db.community.tools.util.ConfigUtils;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
@Slf4j
@Component
public class ApplicationLifecycleAdapter implements ISysApplicationLifecycleService {

    private final ApplicationContext applicationContext;

    @Autowired
    private IDbConnectionContextService connectionContextService;

    public ApplicationLifecycleAdapter(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * On shutdown, roll back and release every console-bound transaction so no connection
     * leaks and no uncommitted work is silently committed by the server closing the socket.
     */
    @PreDestroy
    public void releaseBoundTransactions() {
        try {
            connectionContextService.releaseAllBoundTransactions();
        } catch (Throwable e) {
            log.error("Failed to release bound transactions on shutdown", e);
        }
    }

    @Override
    public String getSystemUuid() {
        ConfigJson configJson = ConfigUtils.getConfig();
        return configJson == null ? null : configJson.getSystemUuid();
    }

    @Override
    public boolean shutdownCliRuntime() {
        Thread thread = new Thread(() -> {
            sleep(200L);
            int exitCode = SpringApplication.exit(applicationContext, () -> 0);
            System.exit(exitCode);
        }, "chat2db-cli-runtime-shutdown");
        thread.setDaemon(false);
        thread.start();
        return true;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
