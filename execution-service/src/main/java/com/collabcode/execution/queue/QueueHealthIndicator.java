package com.collabcode.execution.queue;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Exposes run queue health and in-flight execution state via Spring Boot Actuator
 * health endpoint (GET /actuator/health) for tests and monitoring.
 */
@Component
public class QueueHealthIndicator implements HealthIndicator {

    private final RunQueueGate gate;

    public QueueHealthIndicator(RunQueueGate gate) {
        this.gate = gate;
    }

    @Override
    public Health health() {
        return Health.up()
                .withDetail("in_flight", gate.inFlight())
                .withDetail("max_queue_length", gate.getMaxQueueLength())
                .withDetail("retry_after_seconds", gate.getRetryAfterSeconds())
                .build();
    }
}

