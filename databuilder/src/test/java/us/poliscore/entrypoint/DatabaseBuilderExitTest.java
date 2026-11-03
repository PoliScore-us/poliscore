package us.poliscore.entrypoint;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class DatabaseBuilderExitTest {
    @Test void unsuccessfulExitIsAnExceptionRatherThanNormalMainReturn() {
        assertThrows(IllegalStateException.class, () -> DatabaseBuilder.failOnUnsuccessfulExit(1, null));
        var failure = new RuntimeException("Database transaction rolled back");
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> DatabaseBuilder.failOnUnsuccessfulExit(0, failure)).getCause());
        assertDoesNotThrow(() -> DatabaseBuilder.failOnUnsuccessfulExit(0, null));
    }

    @Test void closesLauncherAfterSuccessfulBuild() {
        var calls = new java.util.ArrayList<String>();
        DatabaseBuilder.runWithShutdown(() -> calls.add("build"), () -> calls.add("shutdown"));
        assertEquals(java.util.List.of("build", "shutdown"), calls);
    }

    @Test void closesLauncherAfterFailedBuildAndPreservesFailure() {
        var failure = new IllegalStateException("Build failed");
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var thrown = assertThrows(IllegalStateException.class, () ->
            DatabaseBuilder.runWithShutdown(() -> { throw failure; }, () -> closed.set(true)));
        assertSame(failure, thrown);
        assertTrue(closed.get());
    }
}
