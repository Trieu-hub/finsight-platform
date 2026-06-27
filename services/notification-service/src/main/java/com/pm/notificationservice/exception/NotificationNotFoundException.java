package com.pm.notificationservice.exception;

/** Thrown when a notification does not exist or does not belong to the caller. */
public class NotificationNotFoundException extends RuntimeException {

    public NotificationNotFoundException(String message) {
        super(message);
    }
}
