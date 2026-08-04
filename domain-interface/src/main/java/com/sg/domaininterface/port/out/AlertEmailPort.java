package com.sg.domaininterface.port.out;

import com.sg.domaininterface.model.alerting.EmailMessage;

/**
 * The mail endpoint.
 *
 * <p>May block: {@link EmailAlertPublisher} only ever calls this from its own dedicated thread, never
 * from a lookup thread. Throwing on transport failure is expected; the publisher retries with backoff
 * and then abandons.
 */
@FunctionalInterface
public interface AlertEmailPort {

    void send(EmailMessage message);

    class EmailDispatchException extends RuntimeException {
        public EmailDispatchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
