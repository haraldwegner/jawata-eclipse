package org.jawata.eclipse.tests;

import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Waiting for something Eclipse does on its own thread, so a test checks the outcome and not the timing: a change notification
 * arriving, an engine starting, a listener coming up. Each wait polls every tenth of a second and gives up with a message that
 * names what was awaited.
 */
final class Await {

    private static final long POLL_MILLIS = 100;

    private Await() {
    }

    /** Waits up to ten seconds for the condition to hold, and says whether it did. */
    static boolean until(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(POLL_MILLIS);
        }
        return condition.getAsBoolean();
    }

    /** Waits up to sixty seconds for a value to appear, and fails naming {@code what} when it does not. */
    static <T> T found(String what, Supplier<Optional<T>> source) throws InterruptedException {
        for (int i = 0; i < 600; i++) {
            Optional<T> value = source.get();
            if (value.isPresent()) {
                return value.get();
            }
            Thread.sleep(POLL_MILLIS);
        }
        throw new AssertionError("within sixty seconds: " + what);
    }
}
