package com.pm.dashboardservice.exception;

/**
 * Raised when an upstream service (transaction/budget/user) is unreachable, times out,
 * or returns an error. The dashboard fails fast: this maps to 502 DASHBOARD_UPSTREAM_ERROR.
 */
public class UpstreamException extends RuntimeException {

    private final String service;

    public UpstreamException(String service, Throwable cause) {
        super("Upstream service '" + service + "' is unavailable", cause);
        this.service = service;
    }

    public String getService() {
        return service;
    }
}
