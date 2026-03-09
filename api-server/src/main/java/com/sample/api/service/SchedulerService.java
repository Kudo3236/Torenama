package com.sample.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    private final QiitaTrendService qiitaTrendService;

    public SchedulerService(QiitaTrendService qiitaTrendService) {
        this.qiitaTrendService = qiitaTrendService;
    }

    // 毎日 09:00 実行
    @Scheduled(cron = "0 0 9 * * *")
    public void updateDaily() {
        log.info("Scheduled update started.");

        try {
            qiitaTrendService.fetchAndSave();
            log.info("Scheduled update succeeded.");
        } catch (Exception e) {
            log.error("Scheduled update failed.", e);
        }
    }
}