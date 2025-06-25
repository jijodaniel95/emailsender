package com.email.emailsender.controller;

import com.email.emailsender.service.PubSubListenerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class HealthController {
    
    private final PubSubListenerService pubSubListenerService;
    
    @Autowired
    public HealthController(PubSubListenerService pubSubListenerService) {
        this.pubSubListenerService = pubSubListenerService;
    }
    
    @GetMapping("/")  // Cloud Run default health check path
    public ResponseEntity<String> root() {
        return ResponseEntity.ok("OK");
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        boolean isHealthy = pubSubListenerService.isHealthy();
        if (isHealthy) {
            return ResponseEntity.ok("Service is healthy");
        } else {
            return ResponseEntity.status(503).body("Service is unhealthy");
        }
    }
    
    @GetMapping("/trigger-pull")
    public ResponseEntity<String> triggerPull() {
        try {
            pubSubListenerService.pullMessages();
            return ResponseEntity.ok("PubSub pull triggered successfully");
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error triggering PubSub pull: " + e.getMessage());
        }
    }
}