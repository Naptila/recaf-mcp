package me.naptila.recafmcp.mcp;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerHealthTest {
    @Test
    void matchingWorkspaceBecomesReady() {
        AtomicBoolean workspaceOpen = new AtomicBoolean();
        WorkerHealth health = new WorkerHealth(
                workspaceOpen::get, "/input.jar", "1.0.0", "4.0.0", "commit");

        assertFalse(health.isReady());
        workspaceOpen.set(true);
        health.markWorkspaceReady("/input.jar");

        assertTrue(health.isReady());
        assertTrue(health.isWorkspaceReady());
        assertEquals("/input.jar", health.getActualInputPath());
    }

    @Test
    void mismatchedWorkspaceRecordsStartupError() {
        WorkerHealth health = new WorkerHealth(
                () -> true, "/expected.jar", "1.0.0", "4.0.0", "commit");

        health.markWorkspaceReady("/other.jar");

        assertFalse(health.isReady());
        assertFalse(health.isWorkspaceReady());
        assertEquals("workspace input path does not match the expected input path", health.getLastError());
    }

    @Test
    void schemaOnlyWorkerIsReadyWithoutWorkspace() {
        WorkerHealth health = new WorkerHealth(
                () -> false, null, "1.0.0", "4.0.0", "commit");

        assertTrue(health.isReady());
        assertFalse(health.isWorkspaceReady());
    }

    @Test
    void operationErrorDoesNotDestroyReadinessAndClearsAfterRetry() {
        WorkerHealth health = new WorkerHealth(
                () -> true, "/input.jar", "1.0.0", "4.0.0", "commit");
        health.markWorkspaceReady("/input.jar");

        health.recordOperationError("save failed");

        assertTrue(health.isReady());
        assertTrue(health.isWorkspaceReady());
        assertEquals("save failed", health.getLastError());

        health.clearOperationError();
        assertTrue(health.isReady());
        assertNull(health.getLastError());
    }
}
