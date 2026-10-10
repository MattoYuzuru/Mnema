package app.mnema.learning.billing;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code billing_receipt}: the «Мой налог» receipt of one paid order and where it is on its way. The copies are what {@link NpdReceipts} writes back
 * under the row lock.
 */
record NpdReceipt(UUID orderId, State state, String serviceName, long amountKopecks, Instant operationTime, Instant deadlineAt, String receiptUuid,
                  boolean cancelRequested, int attempts, Instant nextAttemptAt, String lastErrorCode, Instant failedAlertedAt, Instant overdueAlertedAt,
                  Instant createdAt, Instant updatedAt, long rowVersion) {

    /**
     * {@code SENDING}: a request may be on the wire, so the receipt must be looked up before anything is sent again. {@code FAILED_PERMANENT}: retrying cannot
     * help, an operator decides. {@code CANCELLED} without a receipt number means the receipt was never registered.
     */
    enum State {
        PENDING, SENDING, REGISTERED, CANCEL_PENDING, CANCELLED, FAILED_PERMANENT;

        /** The states the worker still has something to do in. */
        boolean open() {
            return this == PENDING || this == SENDING || this == CANCEL_PENDING;
        }
    }

    /** A new receipt, waiting to be sent. */
    static NpdReceipt pending(UUID orderId, String serviceName, long amountKopecks, Instant operationTime, Instant deadlineAt, Instant now) {
        return new NpdReceipt(orderId, State.PENDING, serviceName, amountKopecks, operationTime, deadlineAt, null, false, 0, now, null, null, null, now, now, 0);
    }

    NpdReceipt in(State to, String uuid, Instant next, String errorCode) {
        return new NpdReceipt(orderId, to, serviceName, amountKopecks, operationTime, deadlineAt, uuid, cancelRequested, attempts, next, errorCode,
                failedAlertedAt, overdueAlertedAt, createdAt, updatedAt, rowVersion);
    }

    /**
     * A refund came while the first request was being made and that request did not go out (the row is back at {@code PENDING}): nothing was registered,
     * so there is nothing to send and nothing to annul.
     */
    NpdReceipt cancelledIfNeverSent() {
        return state == State.PENDING && cancelRequested ? in(State.CANCELLED, null, nextAttemptAt, lastErrorCode) : this;
    }

    NpdReceipt withAttempts(int newAttempts) {
        return new NpdReceipt(orderId, state, serviceName, amountKopecks, operationTime, deadlineAt, receiptUuid, cancelRequested, newAttempts, nextAttemptAt,
                lastErrorCode, failedAlertedAt, overdueAlertedAt, createdAt, updatedAt, rowVersion);
    }

    NpdReceipt withCancelRequested() {
        return new NpdReceipt(orderId, state, serviceName, amountKopecks, operationTime, deadlineAt, receiptUuid, true, attempts, nextAttemptAt, lastErrorCode,
                failedAlertedAt, overdueAlertedAt, createdAt, updatedAt, rowVersion);
    }

    NpdReceipt failedAlerted(Instant at) {
        return new NpdReceipt(orderId, state, serviceName, amountKopecks, operationTime, deadlineAt, receiptUuid, cancelRequested, attempts, nextAttemptAt,
                lastErrorCode, at, overdueAlertedAt, createdAt, updatedAt, rowVersion);
    }

    NpdReceipt overdueAlerted(Instant at) {
        return new NpdReceipt(orderId, state, serviceName, amountKopecks, operationTime, deadlineAt, receiptUuid, cancelRequested, attempts, nextAttemptAt,
                lastErrorCode, failedAlertedAt, at, createdAt, updatedAt, rowVersion);
    }
}
