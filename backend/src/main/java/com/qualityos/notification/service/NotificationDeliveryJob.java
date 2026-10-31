package com.qualityos.notification.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "qualityos.jobs.enabled", havingValue = "true")
class NotificationDeliveryJob {

    private final NotificationDeliveryService delivery;

    NotificationDeliveryJob(NotificationDeliveryService delivery) {
        this.delivery = delivery;
    }

    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT30S")
    void deliver() {
        delivery.deliverPending();
    }
}
