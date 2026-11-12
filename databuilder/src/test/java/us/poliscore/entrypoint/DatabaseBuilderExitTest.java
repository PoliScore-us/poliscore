package us.poliscore.entrypoint;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

    @ParameterizedTest
    @CsvSource({"exception,1", "fatal,1", "success,0", "invalid-arguments,2"})
    void commandOutcomeReachesTheProcessExitCode(String outcome,int expected,@TempDir Path directory) throws Exception {
        var output=directory.resolve("output.txt");
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
            "-cp",System.getProperty("java.class.path"),ExitProbe.class.getName(),outcome)
            .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(30,TimeUnit.SECONDS),"Builder exit probe timed out");
            String log=Files.readString(output);
            assertEquals(expected,process.exitValue(),log);
            assertEquals(expected==0,log.contains("BUILDER_RETURNED_SUCCESSFULLY"),log);
        } finally {
            if(process.isAlive())process.destroyForcibly();
        }
    }

    public static class ExitProbe extends DatabaseBuilder {
        private String outcome;
        @Override public Integer call() {
            if(outcome.equals("exception"))throw new IllegalArgumentException("Synthetic carryover failure");
            return outcome.equals("fatal")?1:0;
        }
        @lombok.SneakyThrows
        public static void main(String[] args) {
            var builder=new ExitProbe();builder.outcome=args[0];
            builder.run(args[0].equals("invalid-arguments")?new String[]{"--invalid-option"}:new String[0]);
            System.out.println("BUILDER_RETURNED_SUCCESSFULLY");
        }
    }
}
