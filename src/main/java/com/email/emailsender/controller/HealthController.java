package com.email.emailsender.controller;

import com.email.emailsender.service.PubSubListenerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class HealthController {
    
    private final PubSubListenerService pubSubListenerService;
    
    public HealthController(PubSubListenerService pubSubListenerService) {
        this.pubSubListenerService = pubSubListenerService;
    }
    
    @GetMapping("/")  // Cloud Run default health check path
    public ResponseEntity<String> root() {
        return ResponseEntity.ok("OK");
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = new HashMap<>();
        status.put("status", "UP");
        
        boolean pubsubHealthy = pubSubListenerService.isHealthy();
        status.put("pubsub", pubsubHealthy ? "UP" : "DOWN");
        
        if (!pubsubHealthy) {
            // Try to trigger a pull immediately if Pub/Sub is not healthy
            try {
                pubSubListenerService.pullMessages();
            } catch (Exception e) {
                // Just log, don't affect the health check response
                status.put("recovery_attempted", true);
            }
        }
        
        return ResponseEntity.ok(status);
    }
}