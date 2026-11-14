package jraft.sim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Appends one JSON operation per line, with logical tick timestamps. */
public final class HistoryRecorder {
    private final Path path;

    public HistoryRecorder(Path path) {
        this.path = path;
        try {
            Files.createDirectories(path.getParent());
            Files.deleteIfExists(path);
            Files.createFile(path);
        } catch (IOException error) {
            throw new IllegalStateException("cannot create history " + path, error);
        }
    }

    public synchronized void record(long clientId, KvCommand command, KvStateMachine.Result result,
                                    long callTick, long returnTick) {
        String output = result == null || result.value() == null ? "null" : quote(result.value());
        String line = "{\"clientId\":" + clientId + ",\"op\":" + quote(command.operation().name().toLowerCase())
                + ",\"key\":" + quote(command.key()) + ",\"value\":" + quote(command.value())
                + ",\"output\":" + output + ",\"call\":" + callTick + ",\"return\":" + returnTick + "}\n";
        try {
            Files.writeString(path, line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException error) {
            throw new IllegalStateException("cannot append history " + path, error);
        }
    }

    private static String quote(String value) {
        if (value == null) return "null";
        StringBuilder escaped = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> { if (c < 0x20) escaped.append(String.format("\\u%04x", (int) c)); else escaped.append(c); }
            }
        }
        return escaped.append('"').toString();
    }
}
