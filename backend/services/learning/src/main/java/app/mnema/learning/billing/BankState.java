package app.mnema.learning.billing;

/**
 * What {@code GetState} (or a payment notification re-checked by it) says about one payment: the facts billing needs and nothing else. {@code amount} is
 * kopecks and null when the bank omitted it; {@code errorCode} is the bank's numeric code ({@code "0"} when fine), never its message.
 */
record BankState(String terminalKey, String orderId, String paymentId, String status, Long amount, boolean success, String errorCode) { }
