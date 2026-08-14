package ai.chat2db.community.jcef.update;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Factory for opening an {@link HttpURLConnection} to a validated URI.
 * Exposed package-private for testing.
 */
@FunctionalInterface
interface ConnectionOpener {

    HttpURLConnection open(URI uri) throws IOException;

    ConnectionOpener DEFAULT = uri -> (HttpURLConnection) uri.toURL().openConnection();
}
