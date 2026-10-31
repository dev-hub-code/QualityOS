package com.qualityos.common.job;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Only where jobs run. The monitor itself is always present, because any instance can show job history. */
@Component
@ConditionalOnProperty(name = "qualityos.jobs.enabled", havingValue = "true")
class StaleJobWarning {

    private static final Logger log = LoggerFactory.getLogger(StaleJobWarning.class);

    private final JobMonitor monitor;

    StaleJobWarning(JobMonitor monitor) {
        this.monitor = monitor;
    }

    @Scheduled(initialDelayString = "${qualityos.jobs.monitor.interval}", fixedDelayString = "${qualityos.jobs.monitor.interval}")
    void warnAboutStaleJobs() {
        monitor.stale(Instant.now()).forEach((job, last) -> log.warn("job={} has not run since {}", job, last));
    }
}
