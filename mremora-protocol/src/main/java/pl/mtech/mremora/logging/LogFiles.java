package pl.mtech.mremora.logging;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Routes {@code System.Logger} / {@code java.util.logging} output to a rotating file, because the packaged launchers
 * have no console.
 *
 * <p>Files are {@code <appName>0.log} (the current one), {@code <appName>1.log}, {@code <appName>2.log}: 1 MiB each,
 * three generations, appended across runs.
 */
public final class LogFiles {

    private static final int LIMIT_BYTES = 1_048_576;
    private static final int GENERATIONS = 3;
    private static final String FORMAT = "%1$tF %1$tT %4$s %3$s: %5$s%6$s%n";

    private static FileHandler handler;
    private static Path logFile;

    private LogFiles() {}

    /**
     * Where log files live: Windows {@code %LOCALAPPDATA%\MRemora\logs} (fallback {@code <home>/AppData/Local}),
     * Linux {@code $XDG_STATE_HOME/mremora/logs} (fallback {@code <home>/.local/state}), anything else
     * {@code <home>/.mremora/logs}.
     */
    public static Path directory(String osName, Map<String, String> env, Path home) {
        if (osName.startsWith("Windows")) {
            String localAppData = env.get("LOCALAPPDATA");
            Path base = localAppData == null || localAppData.isBlank()
                    ? home.resolve("AppData").resolve("Local")
                    : Path.of(localAppData);
            return base.resolve("MRemora").resolve("logs");
        }
        if (osName.startsWith("Linux")) {
            String xdg = env.get("XDG_STATE_HOME");
            Path base = xdg == null || xdg.isBlank() ? home.resolve(".local").resolve("state") : Path.of(xdg);
            return base.resolve("mremora").resolve("logs");
        }
        return home.resolve(".mremora").resolve("logs");
    }

    /**
     * Installs the file handler on the root logger and an uncaught-exception handler that logs {@code SEVERE}.
     * Idempotent per JVM: later calls return the first path.
     *
     * @return the current log file, or {@code null} if it could not be set up (the reason goes to stderr and the
     *         application keeps running without file logging)
     */
    public static Path install(String appName) {
        return install(
                appName,
                directory(System.getProperty("os.name", ""), System.getenv(), Path.of(System.getProperty("user.home"))));
    }

    static synchronized Path install(String appName, Path dir) {
        if (logFile != null) {
            return logFile;
        }
        try {
            Files.createDirectories(dir);
            FileHandler fh = new FileHandler(dir.toString().replace("%", "%%") + File.separator + appName + "%g.log", LIMIT_BYTES, GENERATIONS, true);
            fh.setEncoding(StandardCharsets.UTF_8.name());
            fh.setFormatter(new LineFormatter());
            Logger.getLogger("").addHandler(fh);
            Thread.setDefaultUncaughtExceptionHandler((t, e) ->
                    Logger.getLogger("uncaught").log(Level.SEVERE, "Uncaught exception in " + t.getName(), e));
            handler = fh;
            logFile = dir.resolve(appName + "0.log");
            return logFile;
        } catch (IOException | RuntimeException e) {
            System.err.println("Cannot set up file logging in " + dir + ": " + e);
            return null;
        }
    }

    /** Same layout as {@code SimpleFormatter} with its format {@code %1$tF %1$tT %4$s %3$s: %5$s%6$s%n}, without touching the global system property. */
    private static final class LineFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            String thrown = "";
            if (record.getThrown() != null) {
                StringWriter sw = new StringWriter();
                record.getThrown().printStackTrace(new PrintWriter(sw));
                thrown = System.lineSeparator() + sw;
            }
            return String.format(
                    FORMAT,
                    ZonedDateTime.ofInstant(record.getInstant(), ZoneId.systemDefault()),
                    record.getSourceClassName(),
                    record.getLoggerName(),
                    record.getLevel().getLocalizedName(),
                    formatMessage(record),
                    thrown);
        }
    }

    static synchronized void flushForTests() {
        if (handler != null) {
            handler.flush();
        }
    }

    static synchronized void uninstallForTests() {
        if (handler != null) {
            Logger.getLogger("").removeHandler(handler);
            handler.close();
        }
        handler = null;
        logFile = null;
        Thread.setDefaultUncaughtExceptionHandler(null);
    }
}
