package com.pm.transactionservice.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Emits an in-service audit trail for state-changing (mutating) actions, kept separate
 * from ordinary application logging under a dedicated {@code AUDIT} logger name so the
 * trail can be filtered/routed on its own. The active correlation id, when present, is
 * already attached to every line via MDC by the request filter.
 *
 * <p>This is the "action/security audit logging inside each service" the charter asks for
 * (distinct from JPA timestamp auditing). It is intentionally lightweight: a structured
 * line, not a persisted audit table.
 */
@Component
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger("AUDIT");

    /**
     * @param action verb, e.g. CREATE / UPDATE / DELETE
     * @param entity the domain object kind, e.g. "transaction"
     * @param entityId the affected entity's id
     * @param userId the acting user (from the JWT)
     */
    public void record(String action, String entity, Object entityId, Long userId) {
        log.info("audit action={} entity={} entityId={} userId={}", action, entity, entityId, userId);
    }
}
