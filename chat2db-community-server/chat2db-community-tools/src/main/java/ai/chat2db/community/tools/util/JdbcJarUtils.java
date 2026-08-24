
package ai.chat2db.community.tools.util;


import ai.chat2db.community.tools.constant.JdbcDriverConstants;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ZipUtil;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;


@Slf4j
public class JdbcJarUtils {

    private static final URI DOWNLOAD_HOST = URI.create(JdbcDriverConstants.DOWNLOAD_URL_HOST);
    private static final Path DRIVER_LIB_DIR = Paths.get(JdbcDriverConstants.DRIVER_LIB_PATH).toAbsolutePath().normalize();

    private static final OkHttpClient async_client = new OkHttpClient.Builder()
            .dispatcher(new Dispatcher(Executors.newFixedThreadPool(20)))
            .build();

    private static final OkHttpClient client = new OkHttpClient();

    static {
        File file = DRIVER_LIB_DIR.toFile();
        if (!file.exists()) {
            file.mkdirs();
        }
    }

    public static void asyncDownload(List<String> urls) throws Exception {
        for (String url : urls) {
            File file = outputFile(url);
            if (file.exists()) {
                continue;
            }
            asyncDownload(url);
        }
    }

    public static void asyncDownload(String url) throws Exception {
        asyncDownload(url, ignored -> { });
    }

    static void asyncDownload(String url, Consumer<IOException> completion) throws IOException {
        requireAllowedDownloadUrl(url);
        File file = outputFile(url);
        deleteIfExists(file);
        String safeUrl = sanitizeUrl(url);
        Request request;
        try {
            request = new Request.Builder().url(url).build();
        } catch (IllegalArgumentException e) {
            throw downloadFailure(safeUrl, e.getClass().getSimpleName());
        }
        async_client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                deleteIfExists(file);
                IOException failure = downloadFailure(safeUrl, e.getClass().getSimpleName());
                log.warn("Async JDBC driver download failed for {} ({})", safeUrl,
                    e.getClass().getSimpleName());
                completion.accept(failure);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response closeableResponse = response) {
                    if (!closeableResponse.isSuccessful()) {
                        deleteIfExists(file);
                        IOException failure = downloadFailure(safeUrl,
                            "HTTP " + closeableResponse.code());
                        log.warn("Async JDBC driver download failed for {} (HTTP {})", safeUrl,
                            closeableResponse.code());
                        completion.accept(failure);
                        return;
                    }
                    writeResponseBody(closeableResponse, file);
                    completion.accept(null);
                } catch (IOException e) {
                    deleteIfExists(file);
                    IOException failure = downloadFailure(safeUrl, e.getClass().getSimpleName());
                    log.warn("Async JDBC driver download failed for {} ({})", safeUrl,
                        e.getClass().getSimpleName());
                    completion.accept(failure);
                }
            }
        });
    }

    public static void download(String url) throws IOException {
        requireAllowedDownloadUrl(url);
        File pathfile = DRIVER_LIB_DIR.toFile();
        if (!pathfile.exists()) {
            pathfile.mkdirs();
        }
        File file = outputFile(url);
        deleteIfExists(file);
        String safeUrl = sanitizeUrl(url);
        Request request;
        try {
            request = new Request.Builder()
                    .addHeader("referer", "https://chat2db.ai")
                    .url(url)
                    .build();
        } catch (IllegalArgumentException e) {
            throw downloadFailure(safeUrl, e.getClass().getSimpleName());
        }
        Response response;
        try {
            response = client.newCall(request).execute();
        } catch (IOException e) {
            throw downloadFailure(safeUrl, e.getClass().getSimpleName());
        }
        try (response) {
            if (!response.isSuccessful()) {
                deleteIfExists(file);
                log.warn("JDBC driver download failed for {} (HTTP {})", safeUrl, response.code());
                throw downloadFailure(safeUrl, "HTTP " + response.code());
            }
            try {
                writeResponseBody(response, file);
            } catch (IOException e) {
                deleteIfExists(file);
                throw downloadFailure(safeUrl, e.getClass().getSimpleName());
            }
        }
    }

    static String sanitizeUrl(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return "<redacted-url>";
            }
            String displayHost = host.contains(":") ? "[" + host + "]" : host;
            String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            return scheme + "://" + displayHost + port + path;
        } catch (URISyntaxException | RuntimeException e) {
            return "<redacted-url>";
        }
    }

    private static File outputFile(String url) throws IOException {
        try {
            URI uri = new URI(url);
            String path = uri.getRawPath();
            String fileName = path == null ? "" : new File(path).getName();
            return driverFile(fileName);
        } catch (URISyntaxException e) {
            throw downloadFailure(sanitizeUrl(url), e.getClass().getSimpleName());
        }
    }

    public static File driverFile(String jarPath) throws IOException {
        String artifactName = requireDriverArtifactName(jarPath);
        Path candidate = DRIVER_LIB_DIR.resolve(artifactName).normalize();
        if (!candidate.startsWith(DRIVER_LIB_DIR)) {
            throw new IOException("Invalid JDBC driver artifact name");
        }
        return candidate.toFile();
    }

    public static String requireDriverArtifactName(String jarPath) throws IOException {
        if (jarPath == null || jarPath.isBlank()) {
            throw new IOException("Missing JDBC driver artifact name");
        }
        if (jarPath.contains("/") || jarPath.contains("\\") || jarPath.contains("..")) {
            throw new IOException("Invalid JDBC driver artifact name");
        }
        if (!jarPath.endsWith(".jar") && !jarPath.endsWith(".zip")) {
            throw new IOException("Unsupported JDBC driver artifact type");
        }
        return jarPath;
    }

    private static void requireAllowedDownloadUrl(String url) throws IOException {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw downloadFailure(sanitizeUrl(url), e.getClass().getSimpleName());
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                || uri.getHost() == null || !uri.getHost().equalsIgnoreCase(DOWNLOAD_HOST.getHost())
                || uri.getPort() != DOWNLOAD_HOST.getPort()) {
            throw downloadFailure(sanitizeUrl(url), "untrusted download host");
        }
        String hostPath = DOWNLOAD_HOST.getRawPath() == null ? "/" : DOWNLOAD_HOST.getRawPath();
        String rawPath = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (!rawPath.startsWith(hostPath)) {
            throw downloadFailure(sanitizeUrl(url), "untrusted download path");
        }
        String fileName = new File(rawPath).getName();
        requireDriverArtifactName(fileName);
    }

    private static void writeResponseBody(Response response, File file) throws IOException {
        if (response.body() == null) {
            throw new IOException("Empty response body");
        }
        try (InputStream is = response.body().byteStream();
             FileOutputStream fos = new FileOutputStream(file)) {
            byte[] buffer = new byte[2048];
            int length;
            while ((length = is.read(buffer)) != -1) {
                fos.write(buffer, 0, length);
            }
            fos.flush();
        }
    }

    private static IOException downloadFailure(String safeUrl, String reason) {
        return new IOException("JDBC driver download failed for " + safeUrl + " (" + reason + ")");
    }

    private static void deleteIfExists(File file) {
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            log.warn("Unable to remove incomplete JDBC driver file {} ({})", file.getName(),
                e.getClass().getSimpleName());
        }
    }

    public static String getNewFullPath(String jarPath) {
        File file = resolveDriverFileUnchecked(jarPath);
        if (file.exists()) {
            file.delete();
        }
        return getFullPath(jarPath);
    }

    public static String getFullPath(String jarPath) {
        if(jarPath.endsWith(".zip")){
            return getFullPathZip(jarPath);
        }
        File file = resolveDriverFileUnchecked(jarPath);
        if (!file.exists()) {
            String url = getDownloadUrl(jarPath);
            try {
                download(url);
            } catch (IOException e) {
                try {
                    download(url);
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
        return file.getAbsolutePath();
    }

    private static String getFullPathZip(String jarPath) {
        File file = resolveDriverFileUnchecked(jarPath);
        File destDir = FileUtil.file(file.getParentFile(), FileUtil.mainName(file));
        if (!file.exists()) {
            String url = getDownloadUrl(jarPath);
            try {
                download(url);
                return ZipUtil.unzip(file,destDir).getAbsolutePath();
            } catch (IOException e) {
                try {
                    download(url);
                    return ZipUtil.unzip(file,destDir).getAbsolutePath();
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }else {
            return destDir.getAbsolutePath();
        }
    }


    private static String getDownloadUrl(String jarPath) {
        try {
            return JdbcDriverConstants.DOWNLOAD_URL_HOST + requireDriverArtifactName(jarPath);
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private static File resolveDriverFileUnchecked(String jarPath) {
        try {
            return driverFile(jarPath);
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }
}
