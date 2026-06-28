package com.pm.analyticsservice.exception;

/** Thrown when a query parameter (year/month/currency) is out of range or malformed. */
public class InvalidAnalyticsRequestException extends RuntimeException {

    public InvalidAnalyticsRequestException(String message) {
        super(message);
    }
}
