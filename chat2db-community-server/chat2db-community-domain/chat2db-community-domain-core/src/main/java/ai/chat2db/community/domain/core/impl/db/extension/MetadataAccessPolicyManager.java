package ai.chat2db.community.domain.core.impl.db.extension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import ai.chat2db.community.domain.api.model.metadata.extension.MetadataAccessContext;
import ai.chat2db.community.domain.api.service.db.extension.IMetadataAccessPolicy;
import org.springframework.stereotype.Component;

@Component
public class MetadataAccessPolicyManager {

    private final List<IMetadataAccessPolicy> policies;

    public MetadataAccessPolicyManager(List<IMetadataAccessPolicy> policies) {
        this.policies = policies == null ? List.of() : List.copyOf(policies);
    }

    public boolean isAllowed(MetadataAccessContext resource) {
        return authorize(List.of(Objects.requireNonNull(resource, "metadata resource"))).get(0);
    }

    public List<Boolean> authorize(List<MetadataAccessContext> resources) {
        if (resources == null || resources.isEmpty()) {
            return List.of();
        }
        List<MetadataAccessContext> immutableResources = List.copyOf(resources);
        List<Boolean> result = new ArrayList<>(Collections.nCopies(resources.size(), Boolean.TRUE));
        for (IMetadataAccessPolicy policy : policies) {
            List<Boolean> policyResult = policy.authorize(immutableResources);
            if (policyResult == null || policyResult.size() != resources.size()
                    || policyResult.stream().anyMatch(Objects::isNull)) {
                throw new IllegalStateException(policy.getClass().getName()
                        + " returned an invalid metadata authorization result");
            }
            for (int index = 0; index < result.size(); index++) {
                result.set(index, result.get(index) && policyResult.get(index));
            }
        }
        return List.copyOf(result);
    }

    public <T> List<T> filter(List<T> values, Function<T, MetadataAccessContext> contextFactory) {
        if (values == null || values.isEmpty()) {
            return values;
        }
        List<MetadataAccessContext> resources = values.stream().map(contextFactory).toList();
        List<Boolean> decisions = authorize(resources);
        List<T> visible = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            if (decisions.get(index)) {
                visible.add(values.get(index));
            }
        }
        return visible;
    }

    public boolean isEmpty() {
        return policies.isEmpty();
    }
}
