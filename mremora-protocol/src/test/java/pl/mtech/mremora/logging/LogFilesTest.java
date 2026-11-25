package pl.mtech.mremora.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogFilesTest {

    private static final Path HOME = Path.of("/home/u");

    @AfterEach
    void cleanUp() {
        LogFiles.uninstallForTests();
    }

    @Test
    void windowsUsesLocalAppData() {
        Path dir = LogFiles.directory("Windows 11", Map.of("LOCALAPPDATA", "/tmp/la"), HOME);

        assertThat(dir).isEqualTo(Path.of("/tmp/la", "MRemora", "logs"));
    }

    @Test
    void windowsWithoutLocalAppDataFallsBackToHome() {
        Path dir = LogFiles.directory("Windows 11", Map.of(), HOME);

        assertThat(dir).isEqualTo(Path.of("/home/u", "AppData", "Local", "MRemora", "logs"));
    }

    @Test
    void linuxUsesXdgStateHome() {
        Path dir = LogFiles.directory("Linux", Map.of("XDG_STATE_HOME", "/x"), HOME);

        assertThat(dir).isEqualTo(Path.of("/x", "mremora", "logs"));
    }

    @Test
    void linuxDefaultsToLocalState() {
        Path dir = LogFiles.directory("Linux", Map.of(), HOME);

        assertThat(dir).isEqualTo(Path.of("/home/u", ".local", "state", "mremora", "logs"));
    }

    @Test
    void otherOsUsesDotDir() {
        Path dir = LogFiles.directory("Mac OS X", Map.of(), HOME);

        assertThat(dir).isEqualTo(Path.of("/home/u", ".mremora", "logs"));
    }

    @Test
    void installWritesLogFile(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("logs");

        Path file = LogFiles.install("test", dir);
        System.getLogger("x").log(Level.INFO, "hello");
        LogFiles.flushForTests();

        assertThat(file).isNotNull().hasParent(dir);
        assertThat(Files.readString(file)).contains("hello");
    }

    @Test
    void installIsIdempotent(@TempDir Path tmp) {
        Path first = LogFiles.install("test", tmp);
        Path second = LogFiles.install("test", tmp.resolve("other"));

        assertThat(second).isEqualTo(first);
        assertThat(tmp.resolve("other")).doesNotExist();
    }

    @Test
    void installReturnsNullWhenDirectoryCannotBeCreated(@TempDir Path tmp) throws IOException {
        Path blocker = Files.writeString(tmp.resolve("file"), "x");

        assertThat(LogFiles.install("test", blocker.resolve("logs"))).isNull();
    }
}
