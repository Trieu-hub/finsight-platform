package com.pm.authservice.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Emits an in-service audit trail for state-changing (mutating) actions, kept separate
 * from ordinary application logging under a dedicated {@code AUDIT} logger name so the
 * trail can be filtered/routed on its own.
 *
 * <p>Used for security-sensitive admin operations (role change, enable/disable, delete).
 * It records the acting admin and the affected target account. Intentionally lightweight:
 * a structured line, not a persisted audit table.
 */
@Component
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger("AUDIT");

    /**
     * @param action verb, e.g. UPDATE_ROLE / SET_ENABLED / DELETE
     * @param entity the domain object kind, e.g. "user"
     * @param entityId the affected entity's id
     * @param actor identifier of the acting admin (email)
     * @param detail optional extra context (e.g. the new role / enabled flag); may be null
     */
    public void record(String action, String entity, Object entityId, String actor, Object detail) {
        log.info("audit action={} entity={} entityId={} actor={} detail={}",
                action, entity, entityId, actor, detail);
    }
}
