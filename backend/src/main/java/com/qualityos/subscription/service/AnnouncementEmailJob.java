package com.qualityos.subscription.service;

import com.qualityos.common.job.JobRunner;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Emails announcements soon after they start. The notification delivery job then sends the emails themselves. */
@Component
@ConditionalOnProperty(name = "qualityos.jobs.enabled", havingValue = "true")
class AnnouncementEmailJob {

    private static final Duration LEASE = Duration.ofMinutes(10);

    private final AnnouncementEmails announcements;
    private final JobRunner jobs;
    private final Duration interval;

    AnnouncementEmailJob(
            AnnouncementEmails announcements,
            JobRunner jobs,
            @Value("${qualityos.jobs.announcement-emails.interval}") Duration interval) {
        this.announcements = announcements;
        this.jobs = jobs;
        this.interval = interval;
    }

    @Scheduled(
            initialDelayString = "${qualityos.jobs.announcement-emails.initial-delay}",
            fixedDelayString = "${qualityos.jobs.announcement-emails.interval}")
    void send() {
        jobs.run("announcement-emails", LEASE, interval, context ->
                jobs.forEachTenant(context, announcements.tenantsAwaiting(), tenant -> {
                    int told = announcements.sendDue();
                    context.processed(told);
                    context.notified(told);
                }));
    }
}
