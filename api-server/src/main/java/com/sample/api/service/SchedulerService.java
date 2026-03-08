package com.sample.api.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SchedulerService {

    private final QiitaTrendService qiitaTrendService;

    public SchedulerService(QiitaTrendService qiitaTrendService) {
        this.qiitaTrendService = qiitaTrendService;
    }

    @Scheduled(cron = "0 0 9 * * *")
    public void updateDaily() {
        try {
            qiitaTrendService.fetchAndSave();
            System.out.println("Scheduled update succeeded.");
        } catch (Exception e) {
            System.err.println("Scheduled update failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}