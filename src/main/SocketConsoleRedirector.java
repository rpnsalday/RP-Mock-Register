package main;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Redirects System.out and System.err to a TCP socket so that a remote server
 * (like the provided SocketPrintServer) can display all logs/prints.
 *
 * Configuration (env vars or -D system properties):
 * - LOG_SERVER_HOST (or -Dlog.server.host)
 * - LOG_SERVER_PORT (or -Dlog.server.port)
 * - LOG_TEE_LOCAL (or -Dlog.tee.local) = true|false (default false)
 *
 * Behavior:
 * - If host/port are not provided, does nothing.
 * - On connection failure, keeps local console and prints a warning.
 * - On success, replaces System.out and System.err.
 *
 * Notes:
 * - Uses UTF-8 for the remote stream.
 * - Adds a JVM shutdown hook to flush and close the socket and to restore
 *   the original System.out/System.err streams.
 * - When tee mode is on, logs are written both locally and to the remote server.
 */
public final class SocketConsoleRedirector {
    // Timestamp formatter used for local/remote diagnostic messages.
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Utility class: no instances allowed.
    private SocketConsoleRedirector() {}

    /**
     * Installs the console redirection if host/port are provided via system properties
     * or environment variables.
     *
     * Resolution order for each setting (first non-empty wins):
     * - host: -Dlog.server.host, then LOG_SERVER_HOST
     * - port: -Dlog.server.port, then LOG_SERVER_PORT
     * - tee:  -Dlog.tee.local,  then LOG_TEE_LOCAL (true/1/yes/y accepted)
     *
     * If configuration is incomplete or invalid, the method returns without changing streams.
     */
    public static void installIfConfigured() {
        String host = firstNonEmpty(
                System.getProperty("log.server.host"),
                System.getenv("LOG_SERVER_HOST")
        );
        String portStr = firstNonEmpty(
                System.getProperty("log.server.port"),
                System.getenv("LOG_SERVER_PORT")
        );
        boolean teeLocal = parseBoolean(firstNonEmpty(
                System.getProperty("log.tee.local"),
                System.getenv("LOG_TEE_LOCAL")
        ));

        if (isBlank(host) || isBlank(portStr)) {
            // Not configured: do nothing to avoid surprising the application.
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portStr.trim());
        } catch (NumberFormatException e) {
            // Keep original streams; just warn locally.
            System.err.println(ts() + "[SocketConsoleRedirector] Invalid LOG_SERVER_PORT: " + portStr);
            return;
        }

        try {
            // Establish TCP connection to the remote log server with a short connect timeout.
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host.trim(), port), 3000);
            socket.setTcpNoDelay(true); // favor low-latency log delivery

            OutputStream sockOut = socket.getOutputStream();
            // Auto-flush is enabled; UTF-8 ensures consistent cross-platform encoding.
            PrintStream remote = new PrintStream(sockOut, true, StandardCharsets.UTF_8);

            // Preserve originals so we can optionally tee and restore on shutdown.
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;

            if (teeLocal) {
                // Mirror output to both local console and remote server.
                System.setOut(new TeePrintStream(originalOut, remote));
                System.setErr(new TeePrintStream(originalErr, remote));
            } else {
                // Redirect entirely to remote server.
                System.setOut(remote);
                System.setErr(remote);
            }

            // Ensure resources are cleaned and streams are restored at JVM shutdown.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { remote.flush(); } catch (Exception ignored) {}
                if (teeLocal) {
                    // TeePrintStream wraps remote; flushing above suffices.
                }
                try { socket.close(); } catch (IOException ignored) {}
                // Optionally restore original streams on shutdown (best-effort).
                try { System.setOut(originalOut); } catch (Exception ignored) {}
                try { System.setErr(originalErr); } catch (Exception ignored) {}
            }));

            // Inform local console (if tee) and remote that redirection is active.
            String msg = ts() + "[SocketConsoleRedirector] Redirecting console to " + host + ":" + port + (teeLocal ? " (tee local)" : "");
            if (teeLocal) originalOut.println(msg); // visible locally in tee mode
            // Also send to remote (works for both tee/non-tee since System.out now points there).
            System.out.println(msg);
        } catch (IOException e) {
            // Connection failed: keep local console and show a clear warning.
            System.err.println(ts() + "[SocketConsoleRedirector] Failed to connect to " + host + ":" + port + " - " + e.getMessage());
        }
    }

    /**
     * Installs the console redirection using explicit parameters.
     *
     * @param host     remote log server hostname or IP; must be non-blank
     * @param port     remote port in range [1, 65535]
     * @param teeLocal if true, mirror logs to both local console and remote
     */
    public static void install(String host, int port, boolean teeLocal) {
        if (isBlank(host) || port < 1 || port > 65535) {
            System.err.println(ts() + "[SocketConsoleRedirector] Invalid host/port: host='" + host + "' port=" + port);
            return;
        }
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host.trim(), port), 3000);
            socket.setTcpNoDelay(true);

            OutputStream sockOut = socket.getOutputStream();
            PrintStream remote = new PrintStream(sockOut, true, StandardCharsets.UTF_8);

            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;

            if (teeLocal) {
                System.setOut(new TeePrintStream(originalOut, remote));
                System.setErr(new TeePrintStream(originalErr, remote));
            } else {
                System.setOut(remote);
                System.setErr(remote);
            }

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { remote.flush(); } catch (Exception ignored) {}
                if (teeLocal) {
                    // TeePrintStream wraps remote; flushing above suffices.
                }
                try { socket.close(); } catch (IOException ignored) {}
                try { System.setOut(originalOut); } catch (Exception ignored) {}
                try { System.setErr(originalErr); } catch (Exception ignored) {}
            }));

            String msg = ts() + "[SocketConsoleRedirector] Redirecting console to " + host + ":" + port + (teeLocal ? " (tee local)" : "");
            if (teeLocal) originalOut.println(msg);
            System.out.println(msg);
        } catch (IOException e) {
            System.err.println(ts() + "[SocketConsoleRedirector] Failed to connect to " + host + ":" + port + " - " + e.getMessage());
        }
    }

    /**
     * Parses a boolean-like string for tee option.
     * Accepted truthy values (case-insensitive, trimmed): "1", "true", "yes", "y".
     * Any other value yields false.
     */
    private static boolean parseBoolean(String s) {
        if (s == null) return false;
        String v = s.trim().toLowerCase();
        return v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("y");
    }

    /** Returns true if the string is null, empty, or whitespace-only. */
    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** Returns the first non-blank string; may return null if both are blank. */
    private static String firstNonEmpty(String a, String b) {
        return !isBlank(a) ? a : b;
    }

    /** Formats a timestamp prefix for diagnostic log lines. */
    private static String ts() {
        return "[" + TS.format(LocalDateTime.now()) + "] ";
    }

    /**
     * A PrintStream that writes to two PrintStreams.
     * This is a shallow delegator that forwards all Print/println/write calls
     * to both underlying streams in sequence (first 'a', then 'b').
     *
     * Notes and caveats:
     * - Error handling: if 'a' throws, 'b' may not execute. For simplicity,
     *   this implementation does not attempt to mask or collect exceptions.
     * - flush/close: both streams are flushed/closed; 'b' is attempted even if 'a' throws.
     * - charset: we call super(a) to reuse 'a' configuration; payload writing is delegated.
     */
    static class TeePrintStream extends PrintStream {
        private final PrintStream a;
        private final PrintStream b;

        /**
         * @param a primary local stream (e.g., original System.out/System.err)
         * @param b secondary remote stream (e.g., socket-backed PrintStream)
         */
        TeePrintStream(PrintStream a, PrintStream b) {
            super(a); // delegate charset/encoding of 'a'
            this.a = Objects.requireNonNull(a);
            this.b = Objects.requireNonNull(b);
        }

        // Ensure both streams observe flush/close, regardless of exceptions.
        @Override public void flush() { try { a.flush(); } finally { b.flush(); } }
        @Override public void close() { try { a.close(); } finally { b.close(); } }

        // Consider the tee in error state if either underlying stream reports error.
        @Override public boolean checkError() { return a.checkError() || b.checkError(); }
        @Override protected void setError() { /* not used */ }
        @Override protected void clearError() { /* not used */ }

        // Forward raw byte/char data to both streams.
        @Override public void write(int c) { a.write(c); b.write(c); }
        @Override public void write(byte[] buf, int off, int len) { a.write(buf, off, len); b.write(buf, off, len); }

        // Mirror all print/println overloads to both streams.
        @Override public void print(boolean x) { a.print(x); b.print(x); }
        @Override public void print(char x) { a.print(x); b.print(x); }
        @Override public void print(int x) { a.print(x); b.print(x); }
        @Override public void print(long x) { a.print(x); b.print(x); }
        @Override public void print(float x) { a.print(x); b.print(x); }
        @Override public void print(double x) { a.print(x); b.print(x); }
        @Override public void print(char[] x) { a.print(x); b.print(x); }
        @Override public void print(String x) { a.print(x); b.print(x); }
        @Override public void print(Object x) { a.print(x); b.print(x); }

        @Override public void println() { a.println(); b.println(); }
        @Override public void println(boolean x) { a.println(x); b.println(x); }
        @Override public void println(char x) { a.println(x); b.println(x); }
        @Override public void println(int x) { a.println(x); b.println(x); }
        @Override public void println(long x) { a.println(x); b.println(x); }
        @Override public void println(float x) { a.println(x); b.println(x); }
        @Override public void println(double x) { a.println(x); b.println(x); }
        @Override public void println(char[] x) { a.println(x); b.println(x); }
        @Override public void println(String x) { a.println(x); b.println(x); }
        @Override public void println(Object x) { a.println(x); b.println(x); }
    }
}
