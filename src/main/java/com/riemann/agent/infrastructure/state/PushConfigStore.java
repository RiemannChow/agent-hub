package com.riemann.agent.infrastructure.state;

import com.riemann.agent.presentation.a2a.protocol.PushNotificationConfig;
import java.util.List;
import java.util.Optional;

/**
 * Storage strategy for A2A push-notification configs. Ownership is checked by the caller before
 * every mutation; the store itself is keyed by task id.
 */
public interface PushConfigStore {

    void set(String taskId, PushNotificationConfig config, int maxConfigs);

    Optional<PushNotificationConfig> get(String taskId, String configId);

    List<PushNotificationConfig> list(String taskId);

    boolean delete(String taskId, String configId);
}
