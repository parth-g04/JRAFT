package jraft.sim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Records completed client operations. Each one is appended as a JSON line (the format read by
 * tools/porcupine-check) and kept in memory for the in-process LinearizabilityChecker.
 */
public final class HistoryRecorder {
    private final Path path;
    private final List<LinearizabilityChecker.Operation> operations = new ArrayList<>();

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

    /** {@code output} is the returned value for GET (null = key absent) and null for writes. */
    public synchronized void record(long clientId, KvCommand command, String output, long call, long returned) {
        String op = command.operation().name().toLowerCase();
        String value = command.operation() == KvCommand.Operation.GET ? null : command.value();
        operations.add(new LinearizabilityChecker.Operation(clientId, op, command.key(), value, output, call, returned));
        String line = "{\"clientId\":" + clientId + ",\"op\":" + quote(op)
                + ",\"key\":" + quote(command.key()) + ",\"value\":" + quote(value)
                + ",\"output\":" + quote(output) + ",\"call\":" + call + ",\"return\":" + returned + "}\n";
        try {
            Files.writeString(path, line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException error) {
            throw new IllegalStateException("cannot append history " + path, error);
        }
    }

    public synchronized List<LinearizabilityChecker.Operation> operations() { return List.copyOf(operations); }
    public Path path() { return path; }

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
