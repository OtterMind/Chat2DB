package ai.chat2db.spi;

import ai.chat2db.community.domain.api.enums.parser.FileSizeUnitEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultSQLFileSplitterCharsetTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void readsSqlUsingTheRequestedCharset() throws Exception {
        Charset charset = Charset.forName("GB18030");
        Path sqlFile = temporaryDirectory.resolve("import.sql");
        Files.writeString(sqlFile, "INSERT INTO notes VALUES ('中文');\n", charset);

        try (DefaultSQLFileSplitter splitter = new DefaultSQLFileSplitter(1, FileSizeUnitEnum.MB,
                sqlFile.toFile(), charset)) {
            assertEquals("INSERT INTO notes VALUES ('中文');\n", splitter.nextContent());
        }
    }
}
