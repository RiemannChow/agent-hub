package com.riemann.agent.infrastructure.state;

import com.riemann.agent.presentation.a2a.protocol.PushNotificationConfig;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Original in-process push-config store. */
@Component
@ConditionalOnProperty(name = "agent.state.backend", havingValue = "local", matchIfMissing = true)
public class LocalPushConfigStore implements PushConfigStore {

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, PushNotificationConfig>>
            configs = new ConcurrentHashMap<>();

    @Override
    public void set(String taskId, PushNotificationConfig config, int maxConfigs) {
        ConcurrentHashMap<String, PushNotificationConfig> forTask =
                configs.computeIfAbsent(taskId, k -> new ConcurrentHashMap<>());
        if (!forTask.containsKey(config.id()) && forTask.size() >= maxConfigs) {
            throw new IllegalArgumentException("Too many push notification configs for this task");
        }
        forTask.put(config.id(), config);
    }

    @Override
    public Optional<PushNotificationConfig> get(String taskId, String configId) {
        ConcurrentHashMap<String, PushNotificationConfig> forTask = configs.get(taskId);
        return Optional.ofNullable(forTask)
                .flatMap(m -> Optional.ofNullable(m.get(configId == null ? "default" : configId)));
    }

    @Override
    public List<PushNotificationConfig> list(String taskId) {
        ConcurrentHashMap<String, PushNotificationConfig> forTask = configs.get(taskId);
        return forTask == null ? List.of() : List.copyOf(forTask.values());
    }

    @Override
    public boolean delete(String taskId, String configId) {
        ConcurrentHashMap<String, PushNotificationConfig> forTask = configs.get(taskId);
        if (forTask == null) {
            return false;
        }
        boolean removed = forTask.remove(configId == null ? "default" : configId) != null;
        if (forTask.isEmpty()) {
            configs.remove(taskId, forTask);
        }
        return removed;
    }
}
