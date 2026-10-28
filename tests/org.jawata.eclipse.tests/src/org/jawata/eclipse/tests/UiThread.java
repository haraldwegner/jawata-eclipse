package org.jawata.eclipse.tests;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.eclipse.swt.widgets.Display;

/** Runs work on the user interface thread for a test and hands back what it read, or the failure it raised. */
final class UiThread {

    private UiThread() {
    }

    /**
     * Runs the work on the user interface thread and waits for it.
     *
     * @param work what to run
     * @return what the work gave
     */
    static <T> T call(Supplier<T> work) {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        Display.getDefault().syncExec(() -> {
            try {
                value.set(work.get());
            } catch (RuntimeException failed) {
                failure.set(failed);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return value.get();
    }
}
