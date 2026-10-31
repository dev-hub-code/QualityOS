package com.qualityos.notification.service;

import com.qualityos.common.job.JobRunner;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "qualityos.jobs.enabled", havingValue = "true")
class NotificationDeliveryJob {

    private static final Duration LEASE = Duration.ofMinutes(10);

    private final NotificationDeliveryService delivery;
    private final JobRunner jobs;
    private final Duration interval;

    NotificationDeliveryJob(
            NotificationDeliveryService delivery,
            JobRunner jobs,
            @Value("${qualityos.jobs.notification-delivery.interval}") Duration interval) {
        this.delivery = delivery;
        this.jobs = jobs;
        this.interval = interval;
    }

    @Scheduled(
            initialDelayString = "${qualityos.jobs.notification-delivery.initial-delay}",
            fixedDelayString = "${qualityos.jobs.notification-delivery.interval}")
    void deliver() {
        jobs.run("notification-delivery", LEASE, interval, delivery::deliverPending);
    }
}
