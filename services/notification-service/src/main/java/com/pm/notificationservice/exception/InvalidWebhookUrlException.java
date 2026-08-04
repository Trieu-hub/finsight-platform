package com.pm.notificationservice.exception;

/** The caller offered a webhook URL this service refuses to call. */
public class InvalidWebhookUrlException extends RuntimeException {

    public InvalidWebhookUrlException(String message) {
        super(message);
    }
}
