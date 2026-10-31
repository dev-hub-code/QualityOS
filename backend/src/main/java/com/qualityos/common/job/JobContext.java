package com.qualityos.common.job;

/** Counts what one run did. A job adds to these as it goes, and the runner records them. */
public final class JobContext {

    private int tenants;
    private int records;
    private int notifications;
    private int skipped;
    private int failures;

    public void tenantProcessed() {
        tenants++;
    }

    public void processed(int count) {
        records += count;
    }

    public void notified(int count) {
        notifications += count;
    }

    public void skipped(int count) {
        skipped += count;
    }

    public void failed() {
        failures++;
    }

    int tenants() {
        return tenants;
    }

    int records() {
        return records;
    }

    int notifications() {
        return notifications;
    }

    int skipped() {
        return skipped;
    }

    int failures() {
        return failures;
    }
}
