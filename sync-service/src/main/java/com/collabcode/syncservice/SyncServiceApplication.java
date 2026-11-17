package com.collabcode.syncservice;

import com.collabcode.syncservice.config.RoomLimitsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RoomLimitsProperties.class)
public class SyncServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(SyncServiceApplication.class, args);
    }
}
