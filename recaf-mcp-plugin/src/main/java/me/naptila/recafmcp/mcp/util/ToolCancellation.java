package me.naptila.recafmcp.mcp.util;

import java.util.concurrent.CancellationException;

/**
 * Cooperative cancellation checks for long-running Worker tool operations.
 */
public final class ToolCancellation {
    private ToolCancellation() {
    }

    public static boolean requested() {
        return Thread.currentThread().isInterrupted();
    }

    public static void check() {
        if (requested()) {
            throw new CancellationException("tool operation was cancelled");
        }
    }

    public static void propagateIfRequested(Throwable failure) {
        if (failure instanceof CancellationException cancellation) {
            throw cancellation;
        }
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        check();
    }
}
