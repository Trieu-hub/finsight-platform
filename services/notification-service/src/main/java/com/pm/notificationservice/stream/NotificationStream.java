package com.pm.notificationservice.stream;

import com.pm.notificationservice.dto.NotificationResponse;
import com.pm.notificationservice.entity.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The live push channel for in-app notifications: an in-memory registry of open SSE connections,
 * keyed by user.
 *
 * <p>Before this, the bell polled every 25 s, so an alert could sit invisible for most of a
 * minute. Now the Kafka consumer that writes a notification also pushes it to every open
 * connection that user has, and it appears immediately. The poll remains in the client as a
 * fallback, but at a much longer interval — SSE is the primary path.
 *
 * <p><b>Scope of the design.</b> The registry is per-process. With one notification-service
 * instance (as deployed) every connection for a user is on this JVM, so a push always reaches
 * them. Running several instances behind a load balancer would need the push fanned out over a
 * shared bus (e.g. a Redis pub/sub topic keyed by userId) because a user's connection may live on
 * a different instance than the consumer that produced the notification. Deliberately not built:
 * a single instance is what this deployment has, and a stale design would be worse than none.
 *
 * <p>Delivery is best-effort. SSE is a cache, not the record — the notification is already durable
 * in MySQL, so a dropped push costs at most the fallback poll interval.
 */
@Component
public class NotificationStream {

    private static final Logger log = LoggerFactory.getLogger(NotificationStream.class);

    /** Connections are re-established by the client; a bounded lifetime sheds leaked ones. */
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    /** Idle SSE connections are killed by proxies; a periodic comment keeps them alive. */
    private static final long HEARTBEAT_MS = 25_000L;

    private final Map<Long, List<SseEmitter>> connections = new ConcurrentHashMap<>();

    /** Opens a stream for one user. The caller (controller) has already authenticated them. */
    public SseEmitter open(Long userId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);

        connections.computeIfAbsent(userId, id -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(e -> remove(userId, emitter));

        // An immediate event tells the browser the stream is live (EventSource/fetch readers
        // otherwise cannot distinguish "connected" from "still connecting").
        try {
            emitter.send(SseEmitter.event().name("open").data("{}"));
        } catch (IOException e) {
            remove(userId, emitter);
        }
        log.debug("SSE opened for userId={} (now {} connection(s))",
                userId, connections.getOrDefault(userId, List.of()).size());
        return emitter;
    }

    /** Pushes a freshly created notification to every stream that user has open. */
    public void publish(Notification notification) {
        List<SseEmitter> emitters = connections.get(notification.getUserId());
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        NotificationResponse payload = NotificationResponse.from(notification);
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("notification").data(payload));
            } catch (Exception e) {
                // The client went away between the registry read and the write. Not an error.
                remove(notification.getUserId(), emitter);
            }
        }
    }

    @Scheduled(fixedRate = HEARTBEAT_MS)
    void heartbeat() {
        connections.forEach((userId, emitters) -> {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (Exception e) {
                    remove(userId, emitter);
                }
            }
        });
    }

    private void remove(Long userId, SseEmitter emitter) {
        List<SseEmitter> emitters = connections.get(userId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            connections.remove(userId, emitters);
        }
    }
}
