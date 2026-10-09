package app.mnema.learning.usage;

import java.util.UUID;

/**
 * The port through which the paywall learns whether the owner may start a payment now: billing implements it (checkout mode, tester list and a
 * configured terminal), usage only reads it, so {@code /plans} never imports the billing context. Without an implementation the answer is
 * {@code UNAVAILABLE}.
 */
public interface CheckoutAvailability {
    boolean available(UUID owner);
}
