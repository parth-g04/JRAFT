package jraft.storage;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import jraft.raftcore.LogEntry;

import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

/** Segmented CRC-protected WAL. A bad record truncates the tail and drops later segments. */
public final class FileStorage implements Storage {
    private static final long DEFAULT_SEGMENT_BYTES = 64L * 1024 * 1024;
    private static final int MAX_RECORD_BYTES = 64 * 1024 * 1024;
    private final Path directory;
    private final Path walDirectory;
    private final long segmentLimit;
    private final HardStateFile hardStateFile;
    private final List<LogEntry> entries = new ArrayList<>();
    // Only used to decide which files to force; iteration order never affects Raft state.
    private final Set<Path> dirtySegments = new HashSet<>();
    /** Exported by Prometheus as raft_fsync_seconds (histogram). */
    private final Timer fsyncTimer;
    private long fsyncCount;
    private long fsyncNanos;

    public FileStorage(Path directory) throws IOException { this(directory, DEFAULT_SEGMENT_BYTES); }

    public FileStorage(Path directory, long segmentLimit) throws IOException {
        this(directory, segmentLimit, Metrics.globalRegistry);
    }

    public FileStorage(Path directory, long segmentLimit, MeterRegistry registry) throws IOException {
        this.fsyncTimer = Timer.builder("raft.fsync")
                .description("Duration of fsync calls on the WAL and hard state")
                .publishPercentileHistogram()
                .register(registry);
        if (segmentLimit < 1) throw new IllegalArgumentException("segment limit must be positive");
        this.directory = directory;
        this.walDirectory = directory.resolve("wal");
        this.segmentLimit = segmentLimit;
        this.hardStateFile = new HardStateFile(directory.resolve("hardstate.bin"));
        Files.createDirectories(walDirectory);
        recoverLog();
    }

    @Override public HardState loadHardState() throws IOException { return hardStateFile.read(); }
    @Override public List<LogEntry> loadLog() { return List.copyOf(entries); }

    @Override
    public void persistHardState(HardState hardState) throws IOException {
        long started = System.nanoTime();
        hardStateFile.write(hardState);
        recordFsync(System.nanoTime() - started);
    }

    @Override
    public void append(List<LogEntry> newEntries) throws IOException {
        for (LogEntry entry : newEntries) {
            long expected = entries.size() + 1L;
            if (entry.index() != expected) throw new IOException("expected WAL index " + expected + " but got " + entry.index());
            byte[] record = encodeRecord(entry);
            Path segment = currentSegment();
            if (Files.exists(segment) && Files.size(segment) > 0
                    && Files.size(segment) + record.length > segmentLimit) {
                segment = segmentPath(entry.index());
            }
            try (FileChannel channel = FileChannel.open(segment, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ByteBuffer buffer = ByteBuffer.wrap(record);
                while (buffer.hasRemaining()) channel.write(buffer);
            }
            dirtySegments.add(segment);
            entries.add(entry);
        }
    }

    @Override
    public void truncateFrom(long index) throws IOException {
        if (index < 1) throw new IllegalArgumentException("WAL indexes start at 1");
        if (index > entries.size()) return;
        List<Path> segments = segments();
        boolean found = false;
        for (Path segment : segments) {
            try (RandomAccessFile file = new RandomAccessFile(segment.toFile(), "rw")) {
                while (file.getFilePointer() < file.length()) {
                    long recordStart = file.getFilePointer();
                    LogEntry entry = readRecord(file);
                    if (entry.index() >= index) {
                        file.setLength(recordStart);
                        dirtySegments.add(segment);
                        found = true;
                        break;
                    }
                }
            }
            if (found) {
                for (Path later : segments) {
                    if (later.getFileName().toString().compareTo(segment.getFileName().toString()) > 0) {
                        Files.deleteIfExists(later);
                        dirtySegments.remove(later);
                    }
                }
                break;
            }
        }
        while (entries.size() >= index) entries.remove(entries.size() - 1);
    }

    @Override
    public void flush() throws IOException {
        for (Path segment : List.copyOf(dirtySegments)) {
            if (!Files.exists(segment)) continue;
            long started = System.nanoTime();
            try (FileChannel channel = FileChannel.open(segment, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            recordFsync(System.nanoTime() - started);
        }
        dirtySegments.clear();
    }

    private void recordFsync(long nanos) {
        fsyncCount++;
        fsyncNanos += nanos;
        fsyncTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override public long fsyncCount() { return fsyncCount; }
    @Override public long fsyncNanos() { return fsyncNanos; }

    private void recoverLog() throws IOException {
        long expectedIndex = 1;
        boolean discardLater = false;
        for (Path segment : segments()) {
            if (discardLater) { Files.deleteIfExists(segment); continue; }
            try (RandomAccessFile file = new RandomAccessFile(segment.toFile(), "rw")) {
                while (file.getFilePointer() < file.length()) {
                    long recordStart = file.getFilePointer();
                    try {
                        LogEntry entry = readRecord(file);
                        if (entry.index() != expectedIndex) throw new IOException("non-contiguous WAL index");
                        entries.add(entry);
                        expectedIndex++;
                    } catch (IOException | RuntimeException corrupt) {
                        file.setLength(recordStart);
                        discardLater = true;
                        break;
                    }
                }
            }
        }
    }

    private List<Path> segments() throws IOException {
        try (var paths = Files.list(walDirectory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".seg"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
    }

    private Path currentSegment() throws IOException {
        List<Path> paths = segments();
        return paths.isEmpty() ? segmentPath(Math.max(1, entries.size() + 1L)) : paths.getLast();
    }

    private Path segmentPath(long firstIndex) {
        return walDirectory.resolve(String.format("%020d.seg", firstIndex));
    }

    private static byte[] encodeRecord(LogEntry entry) throws IOException {
        byte[] command = entry.command();
        ByteBuffer payload = ByteBuffer.allocate(1 + Long.BYTES + Long.BYTES + Integer.BYTES + command.length);
        payload.put((byte) 1).putLong(entry.index()).putLong(entry.term()).putInt(command.length).put(command);
        CRC32 crc = new CRC32();
        crc.update(payload.array());
        ByteBuffer record = ByteBuffer.allocate(Integer.BYTES * 2 + payload.capacity());
        record.putInt(payload.capacity()).putInt((int) crc.getValue()).put(payload.array());
        return record.array();
    }

    private static LogEntry readRecord(RandomAccessFile file) throws IOException {
        int length;
        int expectedCrc;
        try {
            length = file.readInt();
            expectedCrc = file.readInt();
        } catch (EOFException incompleteHeader) {
            throw new IOException("incomplete WAL record header", incompleteHeader);
        }
        if (length < 1 + Long.BYTES + Long.BYTES + Integer.BYTES || length > MAX_RECORD_BYTES) {
            throw new IOException("invalid WAL record length " + length);
        }
        byte[] payload = new byte[length];
        try {
            file.readFully(payload);
        } catch (EOFException incompleteBody) {
            throw new IOException("incomplete WAL record body", incompleteBody);
        }
        CRC32 crc = new CRC32();
        crc.update(payload);
        if ((int) crc.getValue() != expectedCrc) throw new IOException("WAL CRC mismatch");
        ByteBuffer data = ByteBuffer.wrap(payload);
        if (data.get() != 1) throw new IOException("unknown WAL record type");
        long index = data.getLong();
        long term = data.getLong();
        int commandLength = data.getInt();
        if (commandLength < 0 || commandLength != data.remaining()) throw new IOException("invalid command length");
        byte[] command = new byte[commandLength];
        data.get(command);
        return new LogEntry(index, term, command);
    }
}
