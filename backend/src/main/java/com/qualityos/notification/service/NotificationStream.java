package com.qualityos.notification.service;

import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Tells a signed-in user's open pages that their notifications changed, so the bell updates at once instead of at the
 * next poll. The event carries nothing but its name: the page reads the inbox as usual, through every usual check.
 *
 * <p>Connections live in this instance's memory. With several instances behind a load balancer, a change made on
 * one does not reach a page connected to another; that page still catches up at its next poll.
 */
@Component
public class NotificationStream {

    static final String EVENT = "notifications";
    /** The connection is closed after this, and the browser opens a new one, with a fresh session check. */
    static final Duration TIMEOUT = Duration.ofMinutes(30);
    /** Enough for a few tabs and devices. Opening another closes the oldest. */
    static final int MAX_PER_USER = 5;
    static final Duration HEARTBEAT = Duration.ofSeconds(25);

    private record Inbox(UUID tenantId, UUID userId) {
    }

    private final Map<Inbox, List<SseEmitter>> open = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("notification-stream-heartbeat").daemon().factory());

    NotificationStream() {
        timer.scheduleWithFixedDelay(this::heartbeat, HEARTBEAT.toSeconds(), HEARTBEAT.toSeconds(), TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
        open.values().forEach(connections -> connections.forEach(SseEmitter::complete));
        open.clear();
    }

    public SseEmitter connect(UUID tenantId, UUID userId) {
        Inbox inbox = new Inbox(tenantId, userId);
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        List<SseEmitter> connections = open.computeIfAbsent(inbox, key -> new CopyOnWriteArrayList<>());
        connections.add(emitter);
        Runnable remove = () -> forget(inbox, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
        while (connections.size() > MAX_PER_USER) {
            SseEmitter oldest = connections.getFirst();
            forget(inbox, oldest);
            oldest.complete();
        }
        // Sent at once, so the browser knows the connection is open and catches up on anything it missed meanwhile.
        send(inbox, emitter, SseEmitter.event().name(EVENT).data("connected"));
        return emitter;
    }

    /** After the caller's transaction commits, so that a page never reads the inbox before the change is in it. */
    void changedAfterCommit(UUID tenantId, Collection<UUID> userIds) {
        Set<UUID> recipients = Set.copyOf(userIds);
        if (recipients.isEmpty() || tenantId == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            changed(tenantId, recipients);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                changed(tenantId, recipients);
            }
        });
    }

    void changed(UUID tenantId, Collection<UUID> userIds) {
        userIds.forEach(userId -> {
            Inbox inbox = new Inbox(tenantId, userId);
            open.getOrDefault(inbox, List.of())
                    .forEach(emitter -> send(inbox, emitter, SseEmitter.event().name(EVENT).data("changed")));
        });
    }

    /**
     * A comment line keeps idle connections from being closed by proxies, and finds the ones already gone. It runs on
     * its own thread rather than as a scheduled job, because pages connect even where background jobs are turned off.
     */
    void heartbeat() {
        // An exception would end the schedule; each send catches its own.
        open.forEach((inbox, connections) ->
                connections.forEach(emitter -> send(inbox, emitter, SseEmitter.event().comment("keep-alive"))));
    }

    int connections() {
        return open.values().stream().mapToInt(List::size).sum();
    }

    private void send(Inbox inbox, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | RuntimeException ex) {
            forget(inbox, emitter);
        }
    }

    private void forget(Inbox inbox, SseEmitter emitter) {
        open.computeIfPresent(inbox, (key, connections) -> {
            connections.remove(emitter);
            return connections.isEmpty() ? null : connections;
        });
    }
}
