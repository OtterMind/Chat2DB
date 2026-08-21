package ai.chat2db.community.domain.core.impl.task.export;

import ai.chat2db.community.tools.exception.ParamBusinessException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ExportStrategyRegistry {

    private final Map<String, IExportStrategy> strategies;

    public ExportStrategyRegistry(List<IExportStrategy> strategies) {
        Map<String, IExportStrategy> registered = new LinkedHashMap<>();
        for (IExportStrategy strategy : strategies) {
            String type = normalize(strategy.type());
            IExportStrategy previous = registered.putIfAbsent(type, strategy);
            if (previous != null) {
                throw new IllegalStateException("Duplicate export strategy type: " + type);
            }
        }
        this.strategies = Map.copyOf(registered);
    }

    public IExportStrategy getExporter(String type) {
        String normalizedType = normalize(type);
        IExportStrategy strategy = strategies.get(normalizedType);
        if (strategy == null) {
            throw new ParamBusinessException(type);
        }
        return strategy;
    }

    private static String normalize(String type) {
        if (StringUtils.isBlank(type)) {
            throw new ParamBusinessException(type);
        }
        return type.toLowerCase(Locale.ROOT);
    }
}
