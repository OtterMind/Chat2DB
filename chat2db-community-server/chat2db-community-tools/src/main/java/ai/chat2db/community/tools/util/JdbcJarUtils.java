
package ai.chat2db.community.tools.util;


import ai.chat2db.community.tools.constant.JdbcDriverConstants;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
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

    private static final OkHttpClient async_client = new OkHttpClient.Builder()
            .dispatcher(new Dispatcher(Executors.newFixedThreadPool(20)))
            .build();

    private static final OkHttpClient client = new OkHttpClient();

    static {
        File file = new File(JdbcDriverConstants.DRIVER_LIB_PATH);
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
        File pathfile = new File(JdbcDriverConstants.DRIVER_LIB_PATH);
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
            if (fileName.isBlank()) {
                throw downloadFailure(sanitizeUrl(url), "missing file name");
            }
            return new File(JdbcDriverConstants.DRIVER_LIB_PATH, fileName);
        } catch (URISyntaxException e) {
            throw downloadFailure(sanitizeUrl(url), e.getClass().getSimpleName());
        }
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
        String path = JdbcDriverConstants.DRIVER_LIB_PATH + jarPath;
        File file = new File(path);
        if (file.exists()) {
            file.delete();
        }
        return getFullPath(jarPath);
    }

    public static String getFullPath(String jarPath) {
        if(jarPath.endsWith(".zip")){
            return getFullPathZip(jarPath);
        }
        String path = JdbcDriverConstants.DRIVER_LIB_PATH + jarPath;
        File file = new File(path);
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
        return path;
    }

    private static String getFullPathZip(String jarPath) {
        String path = JdbcDriverConstants.DRIVER_LIB_PATH + jarPath;
        File file = new File(path);
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
        return JdbcDriverConstants.DOWNLOAD_URL_HOST + jarPath;
    }
}
