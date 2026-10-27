package cam72cam.immersiverailroading.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Explicit result of loading external rolling-stock and track packs.
 * Failures keep the file, the pack name, the message, and the causal chain instead of a one-line log.
 */
public final class PackLoadReport {
    public enum Status {
        LOADED,
        SKIPPED,
        FAILED
    }

    public static final class Entry {
        public final Status status;
        public final String id;
        public final String pack;
        public final String message;
        public final String detail;

        Entry(Status status, String id, String pack, String message, String detail) {
            this.status = status;
            this.id = id == null ? "" : id;
            this.pack = pack == null || pack.isEmpty() ? "unknown pack" : pack;
            this.message = message == null ? "" : message;
            this.detail = detail == null ? "" : detail;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public synchronized void loaded(String id, String pack) {
        entries.add(new Entry(Status.LOADED, id, pack, "", ""));
    }

    public synchronized void skipped(String id, String reason) {
        entries.add(new Entry(Status.SKIPPED, id, "", reason, ""));
    }

    public synchronized void failed(String id, String pack, String message, Throwable error) {
        entries.add(new Entry(Status.FAILED, id, pack, message, describe(error)));
    }

    public synchronized List<Entry> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public synchronized int count(Status status) {
        int count = 0;
        for (Entry entry : entries) {
            if (entry.status == status) {
                count++;
            }
        }
        return count;
    }

    public synchronized boolean hasFailures() {
        return count(Status.FAILED) > 0;
    }

    public synchronized String format() {
        int loaded = count(Status.LOADED);
        int skipped = count(Status.SKIPPED);
        int failed = count(Status.FAILED);
        StringBuilder out = new StringBuilder();
        out.append("Immersive Railroading pack load: loaded ").append(loaded)
                .append(", skipped ").append(skipped)
                .append(", failed ").append(failed)
                .append('\n');
        for (Entry entry : entries) {
            if (entry.status == Status.LOADED) {
                continue;
            }
            out.append(entry.status).append(' ').append(entry.id);
            if (entry.status == Status.FAILED) {
                out.append(" (pack ").append(entry.pack).append(')');
            }
            out.append('\n');
            if (!entry.message.isEmpty()) {
                out.append("  ").append(entry.message).append('\n');
            }
            if (!entry.detail.isEmpty()) {
                out.append(entry.detail);
                if (!entry.detail.endsWith("\n")) {
                    out.append('\n');
                }
            }
        }
        if (failed == 0) {
            out.append("No pack errors.\n");
        }
        return out.toString();
    }

    public static String rootMessage(Throwable error) {
        if (error == null) {
            return "";
        }
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (message == null || message.isEmpty()) {
            message = root.getClass().getSimpleName();
        }
        if (root == error) {
            return error.getClass().getSimpleName() + ": " + message;
        }
        return error.getClass().getSimpleName() + ": " + safe(error.getMessage())
                + " caused by " + root.getClass().getSimpleName() + ": " + message;
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 6) {
            out.append("  ");
            if (depth == 0) {
                out.append(rootMessage(error));
            } else {
                out.append("Caused by ").append(current.getClass().getName());
                if (current.getMessage() != null) {
                    out.append(": ").append(current.getMessage());
                }
            }
            out.append('\n');
            StackTraceElement[] trace = current.getStackTrace();
            int shown = Math.min(trace.length, 8);
            for (int i = 0; i < shown; i++) {
                out.append("    at ").append(trace[i]).append('\n');
            }
            if (trace.length > shown) {
                out.append("    ... ").append(trace.length - shown).append(" more\n");
            }
            current = current.getCause();
            depth++;
        }
        return out.toString();
    }

    private static String safe(String message) {
        return message == null || message.isEmpty() ? "no message" : message;
    }
}
