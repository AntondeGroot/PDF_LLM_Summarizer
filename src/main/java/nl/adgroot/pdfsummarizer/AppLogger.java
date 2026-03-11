package nl.adgroot.pdfsummarizer;

import java.io.IOException;
import java.util.logging.*;
import nl.adgroot.pdfsummarizer.config.AppConfig;

public class AppLogger {

    private static final String RESET  = "\u001B[0m";
    private static final String GRAY   = "\u001B[90m";
    private static final String WHITE  = "\u001B[97m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED    = "\u001B[31m";

    private static final Logger ROOT = Logger.getLogger("nl.adgroot.pdfsummarizer");

    static {
        ROOT.setUseParentHandlers(false);

        // Console handler — coloured output, INFO+WARN to stdout, ERROR to stderr
        Handler stdoutHandler = new StreamHandler(System.out, new Formatter() {
            @Override
            public String format(LogRecord record) {
                String color = colorFor(record.getLevel());
                return color + "[" + label(record.getLevel()) + "] " + record.getMessage() + RESET + System.lineSeparator();
            }
        }) {
            @Override
            public synchronized void publish(LogRecord record) {
                if (record.getLevel().intValue() < Level.SEVERE.intValue()) {
                    super.publish(record);
                    flush();
                }
            }
        };
        stdoutHandler.setLevel(Level.ALL);

        Handler stderrHandler = new StreamHandler(System.err, new Formatter() {
            @Override
            public String format(LogRecord record) {
                String msg = RED + "[" + label(record.getLevel()) + "] " + record.getMessage() + RESET + System.lineSeparator();
                if (record.getThrown() != null) {
                    msg += RED + stackTraceOf(record.getThrown()) + RESET;
                }
                return msg;
            }
        }) {
            @Override
            public synchronized void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.SEVERE.intValue()) {
                    super.publish(record);
                    flush();
                }
            }
        };
        stderrHandler.setLevel(Level.ALL);

        // File handler — plain text, no ANSI codes
        try {
            FileHandler fileHandler = new FileHandler("app.log", /* append= */ true);
            fileHandler.setLevel(Level.ALL);
            fileHandler.setFormatter(new Formatter() {
                @Override
                public String format(LogRecord r) {
                    String line = "[" + label(r.getLevel()) + "] " + r.getMessage() + System.lineSeparator();
                    if (r.getThrown() != null) {
                        line += stackTraceOf(r.getThrown());
                    }
                    return line;
                }
            });
            ROOT.addHandler(fileHandler);
        } catch (IOException e) {
            ROOT.warning("AppLogger: could not open log file: " + e.getMessage());
        }

        ROOT.addHandler(stdoutHandler);
        ROOT.addHandler(stderrHandler);
        ROOT.setLevel(Level.INFO);
    }

    private static String colorFor(Level level) {
        if (level.intValue() <= Level.FINE.intValue())    return GRAY;
        if (level.intValue() <= Level.INFO.intValue())    return WHITE;
        if (level.intValue() <= Level.WARNING.intValue()) return YELLOW;
        return RED;
    }

    private static String label(Level level) {
        if (level == Level.FINE)    return "DEBUG";
        if (level == Level.INFO)    return "INFO";
        if (level == Level.WARNING) return "WARN";
        if (level == Level.SEVERE)  return "ERROR";
        return level.getName();
    }

    private static String stackTraceOf(Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t).append(System.lineSeparator());
        for (StackTraceElement e : t.getStackTrace()) {
            sb.append("\tat ").append(e).append(System.lineSeparator());
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------

    private final Logger logger;

    private AppLogger(Class<?> clazz) {
        this.logger = Logger.getLogger(clazz.getName());
    }

    public static AppLogger getLogger(Class<?> clazz) {
        return new AppLogger(clazz);
    }

    public void info(String msg) { logger.info(msg); }
    public void info(String fmt, Object... args) {
        if (logger.isLoggable(Level.INFO)) logger.info(String.format(fmt, args));
    }

    public void debug(String msg) { logger.fine(msg); }
    public void debug(String fmt, Object... args) {
        if (logger.isLoggable(Level.FINE)) logger.fine(String.format(fmt, args));
    }

    public void warn(String msg) { logger.warning(msg); }
    public void warn(String fmt, Object... args) {
        if (logger.isLoggable(Level.WARNING)) logger.warning(String.format(fmt, args));
    }

    public void error(String msg) { logger.severe(msg); }
    public void error(String msg, Throwable t) { logger.log(Level.SEVERE, msg, t); }
    public void error(String fmt, Object... args) {
        if (logger.isLoggable(Level.SEVERE)) logger.severe(String.format(fmt, args));
    }

    /** Apply log level from config. Call once after ConfigLoader.load(). */
    public static void configure(AppConfig cfg) {
        Level level = switch (cfg.logging.level.toUpperCase()) {
            case "DEBUG" -> Level.FINE;
            case "WARN"  -> Level.WARNING;
            case "ERROR" -> Level.SEVERE;
            default      -> Level.INFO;
        };
        ROOT.setLevel(level);
        for (Handler h : ROOT.getHandlers()) h.setLevel(level);
    }
}