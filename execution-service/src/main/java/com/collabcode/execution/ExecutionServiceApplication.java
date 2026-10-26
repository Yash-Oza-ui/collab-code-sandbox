package com.collabcode.execution;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for execution-service (T-03).
 * Port 8082 – see application.yml.
 * Health: GET /actuator/health (T-06 section 1)
 * Run:    POST /v1/runs       (T-06 section 4)
 */
@SpringBootApplication
public class ExecutionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExecutionServiceApplication.class, args);
    }
}
