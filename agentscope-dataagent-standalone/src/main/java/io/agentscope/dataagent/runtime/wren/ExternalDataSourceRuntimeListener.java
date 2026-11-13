package io.agentscope.dataagent.runtime.wren;

import io.agentscope.dataagent.dataset.ExternalDataSourceConnectionChanged;
import io.agentscope.dataagent.dataset.WrenProfileHome;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Refresh derived credentials after commit; permission enforcement does not depend on eviction. */
@Component
public class ExternalDataSourceRuntimeListener {
    private final DatasetRepository datasets;
    private final WrenProfileHome profiles;
    private final WrenInstanceRegistry instances;

    public ExternalDataSourceRuntimeListener(
            DatasetRepository datasets, WrenProfileHome profiles, WrenInstanceRegistry instances) {
        this.datasets = datasets;
        this.profiles = profiles;
        this.instances = instances;
    }

    @TransactionalEventListener
    public void changed(ExternalDataSourceConnectionChanged event) {
        try {
            profiles.ensure();
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger(getClass())
                    .warn("Source profile refresh failed for {}", event.sourceId());
        }
        for (String group : datasets.findGroupIdsByExternalSource(event.sourceId())) {
            try {
                instances.invalidate(group);
            } catch (RuntimeException failure) {
                LoggerFactory.getLogger(getClass())
                        .warn("Source instance eviction failed for group {}", group);
            }
        }
    }
}
