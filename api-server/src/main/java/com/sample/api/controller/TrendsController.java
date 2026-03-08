package com.sample.api.controller;

import com.sample.api.service.QiitaTrendService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class TrendsController {

    private final QiitaTrendService qiitaTrendService;

    public TrendsController(QiitaTrendService qiitaTrendService) {
        this.qiitaTrendService = qiitaTrendService;
    }

    @GetMapping("/healthz")
    public String healthz() {
        return "ok";
    }

    @GetMapping(value = "/mock/trends", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getMockTrends() {
        try {
            return ResponseEntity.ok(qiitaTrendService.readSavedTrends());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("{\"error\":\"Failed to read trends\"}");
        }
    }

    @PostMapping(value = "/admin/update", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> updateNow() {
        try {
            return ResponseEntity.ok(qiitaTrendService.fetchAndSave());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("{\"error\":\"Failed to update trends: " + escape(e.getMessage()) + "\"}");
        }
    }

    private String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\"", "\\\"");
    }
}