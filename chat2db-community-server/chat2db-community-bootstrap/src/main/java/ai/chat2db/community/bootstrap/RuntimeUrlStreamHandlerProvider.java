package ai.chat2db.community.bootstrap;

import java.net.URLStreamHandler;
import java.net.spi.URLStreamHandlerProvider;
import java.util.Map;

/** Exposes the isolated Boot runtime's protocols through the JDK's system-loader discovery. */
public final class RuntimeUrlStreamHandlerProvider extends URLStreamHandlerProvider {
    private static volatile Map<String, URLStreamHandler> handlers = Map.of();

    static void register(ClassLoader runtime) throws ReflectiveOperationException {
        // The JDK never consults providers for "jar", so only the nested protocol is registered here.
        handlers = Map.of("nested", handler(runtime, "nested"));
    }

    private static URLStreamHandler handler(ClassLoader runtime, String protocol) throws ReflectiveOperationException {
        return (URLStreamHandler) Class.forName("org.springframework.boot.loader.net.protocol." + protocol + ".Handler",
                true, runtime).getConstructor().newInstance();
    }

    @Override
    public URLStreamHandler createURLStreamHandler(String protocol) {
        return handlers.get(protocol);
    }
}
