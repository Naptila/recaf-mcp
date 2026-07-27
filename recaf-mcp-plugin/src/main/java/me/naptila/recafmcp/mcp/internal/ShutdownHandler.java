package me.naptila.recafmcp.mcp.internal;

import me.naptila.recafmcp.mcp.WorkerOperationCoordinator;

import java.util.Objects;

/**
 * Coordinates shutdown with workspace operations and defers exit until after response delivery.
 */
public final class ShutdownHandler {
    private final WorkerOperationCoordinator operationCoordinator;
    private final Runnable shutdownAction;

    public ShutdownHandler(WorkerOperationCoordinator operationCoordinator, Runnable shutdownAction) {
        this.operationCoordinator = Objects.requireNonNull(
                operationCoordinator,
                "operationCoordinator"
        );
        this.shutdownAction = Objects.requireNonNull(shutdownAction, "shutdownAction");
    }

    public void prepare() throws InterruptedException {
        operationCoordinator.beginShutdown();
    }

    public void afterResponseSent() {
        shutdownAction.run();
    }
}
