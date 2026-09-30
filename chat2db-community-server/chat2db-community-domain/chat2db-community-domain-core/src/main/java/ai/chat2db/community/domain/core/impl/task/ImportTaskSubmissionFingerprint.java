package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.task.ImportTableSource;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import com.alibaba.fastjson2.JSON;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Produces a stable identity for the semantic import request before staged files are claimed. */
final class ImportTaskSubmissionFingerprint {

    private ImportTaskSubmissionFingerprint() {
    }

    static String create(ImportTaskSpec spec, String stagedFileId) {
        ImportTaskSpec canonical = JSON.parseObject(JSON.toJSONString(spec), ImportTaskSpec.class);
        canonical.setClientSubmissionId(null);
        canonical.setClientSubmissionFingerprint(null);

        String topLevelFileId = StringUtils.firstNonBlank(stagedFileId, canonical.getImportFileId());
        if (StringUtils.isNotBlank(topLevelFileId)) {
            canonical.setImportFileId(topLevelFileId);
            canonical.setSourceFile(null);
        }
        if (canonical.getTableSources() != null) {
            for (ImportTableSource source : canonical.getTableSources()) {
                if (source != null && StringUtils.isNotBlank(source.getImportFileId())) {
                    source.setSourceFile(null);
                }
            }
        }

        Object jsonValue = JSON.parse(JSON.toJSONString(canonical));
        byte[] canonicalBytes = JSON.toJSONString(sortObjectKeys(jsonValue)).getBytes(StandardCharsets.UTF_8);
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static Object sortObjectKeys(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), sortObjectKeys(item)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> sorted = new ArrayList<>(list.size());
            list.forEach(item -> sorted.add(sortObjectKeys(item)));
            return sorted;
        }
        return value;
    }
}
