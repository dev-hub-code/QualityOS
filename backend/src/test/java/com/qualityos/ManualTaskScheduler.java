package com.qualityos;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

/** Stands in for the real scheduler. Spring hands it the same tasks, and time moves only when a test moves it. */
class ManualTaskScheduler implements TaskScheduler {

    private static final class Task {
        private final Runnable work;
        private final Duration delay;
        private final Duration initialDelay;
        private Instant due;

        private Task(Runnable work, Instant due, Duration initialDelay, Duration delay) {
            this.work = work;
            this.due = due;
            this.initialDelay = initialDelay;
            this.delay = delay;
        }
    }

    private final List<Task> tasks = new ArrayList<>();
    private Instant now = Instant.now();

    int registered() {
        return tasks.size();
    }

    List<Duration> delays() {
        return tasks.stream().map(task -> task.delay).toList();
    }

    /** As after a restart: every task is waiting for its first run again. */
    void restart() {
        tasks.forEach(task -> task.due = now.plus(task.initialDelay));
    }

    /** Moves time on, running each task as it falls due, in order, on this thread. */
    void advance(Duration by) {
        Instant end = now.plus(by);
        while (true) {
            Task next = tasks.stream().filter(task -> !task.due.isAfter(end))
                    .min(Comparator.comparing(task -> task.due)).orElse(null);
            if (next == null) {
                break;
            }
            now = next.due;
            next.work.run();
            next.due = now.plus(next.delay);
        }
        now = end;
    }

    @Override
    public Clock getClock() {
        return new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
        tasks.add(new Task(task, startTime, Duration.between(now, startTime), delay));
        return null;
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
        return scheduleWithFixedDelay(task, now.plus(delay), delay);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
        throw new UnsupportedOperationException();
    }
}
