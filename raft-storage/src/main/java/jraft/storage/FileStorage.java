package jraft.storage;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import jraft.raftcore.LogEntry;
import jraft.raftcore.Snapshot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

/**
 * Segmented, CRC-protected WAL plus hard state and snapshot files (ARCHITECTURE.md section 2):
 * <pre>
 * hardstate.bin
 * wal/&lt;firstIndex&gt;.seg     records [len:4][crc32:4][type:1][index:8][term:8][cmdLen:4][cmd]
 * snapshots/snap-&lt;index&gt;-&lt;term&gt;.bin
 * </pre>
 * Recovery: load the newest valid snapshot, replay WAL records, truncate at the first bad CRC or
 * torn record (later segments are deleted), skip records the snapshot covers, and finish any
 * compaction a crash interrupted.
 */
public final class FileStorage implements Storage {
    private static final long DEFAULT_SEGMENT_BYTES = 64L * 1024 * 1024;
    private static final int MAX_RECORD_BYTES = 64 * 1024 * 1024;
    private static final int RECORD_HEADER_BYTES = Integer.BYTES * 2;
    private static final int PAYLOAD_FIXED_BYTES = 1 + Long.BYTES + Long.BYTES + Integer.BYTES;

    /** One WAL file; {@code firstIndex} comes from its name. */
    private record Segment(long firstIndex, Path path) { }
    private record Parsed(List<LogEntry> entries, List<Long> offsets, long validBytes) { }

    private final Path walDirectory;
    private final long segmentLimit;
    private final HardStateFile hardStateFile;
    private final SnapshotFile snapshotFile;
    /** Entries baseIndex+1 .. lastIndex(), in memory. */
    private final List<LogEntry> entries = new ArrayList<>();
    private final List<Segment> segments = new ArrayList<>();
    /** Files written or truncated since the last flush. Sorted only for reproducibility. */
    private final Set<Path> dirty = new TreeSet<>();
    private long baseIndex;
    private Snapshot snapshot = Snapshot.EMPTY;
    private FileChannel active;          // open channel of segments.getLast(), or null
    private long activeSize;
    /** Exported by Prometheus as raft_fsync_seconds (histogram). */
    private final Timer fsyncTimer;
    private long fsyncCount;
    private long fsyncNanos;

    public FileStorage(Path directory) throws IOException { this(directory, DEFAULT_SEGMENT_BYTES); }

    public FileStorage(Path directory, long segmentLimit) throws IOException {
        this(directory, segmentLimit, Metrics.globalRegistry);
    }

    public FileStorage(Path directory, long segmentLimit, MeterRegistry registry) throws IOException {
        if (segmentLimit < 1) throw new IllegalArgumentException("segment limit must be positive");
        this.fsyncTimer = Timer.builder("raft.fsync")
                .description("Duration of fsync calls on the WAL, hard state and snapshots")
                .publishPercentileHistogram()
                .register(registry);
        this.walDirectory = directory.resolve("wal");
        this.segmentLimit = segmentLimit;
        this.hardStateFile = new HardStateFile(directory.resolve("hardstate.bin"));
        this.snapshotFile = new SnapshotFile(directory.resolve("snapshots"));
        Files.createDirectories(walDirectory);
        recover();
    }

    @Override public HardState loadHardState() throws IOException { return hardStateFile.read(); }
    @Override public List<LogEntry> loadLog() { return List.copyOf(entries); }
    @Override public Snapshot loadSnapshot() { return snapshot; }

    @Override
    public void persistHardState(HardState hardState) throws IOException {
        long started = System.nanoTime();
        hardStateFile.write(hardState);
        recordFsync(System.nanoTime() - started);
    }

    @Override
    public void saveSnapshot(Snapshot newSnapshot) throws IOException {
        recordFsync(snapshotFile.write(newSnapshot));
        snapshot = newSnapshot;
    }

    @Override
    public void append(List<LogEntry> newEntries) throws IOException {
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        for (LogEntry entry : newEntries) {
            long expected = lastIndex() + 1;
            if (entry.index() != expected) throw new IOException("expected WAL index " + expected + " but got " + entry.index());
            byte[] record = encodeRecord(entry);
            if (active == null || (activeSize + pending.size() > 0
                    && activeSize + pending.size() + record.length > segmentLimit)) {
                writePending(pending);
                roll(entry.index());
            }
            pending.write(record);
            entries.add(entry);
        }
        writePending(pending);
    }

    @Override
    public void truncateFrom(long index) throws IOException {
        if (index > lastIndex()) return;
        if (index <= baseIndex) throw new IllegalArgumentException("cannot truncate compacted entries at " + index);
        // The segment holding `index` is the last one whose first index is <= index.
        int holder = segments.size() - 1;
        while (holder > 0 && segments.get(holder).firstIndex() > index) holder--;
        for (int later = segments.size() - 1; later > holder; later--) deleteSegment(later);
        Segment segment = segments.get(holder);
        Parsed parsed = parse(Files.readAllBytes(segment.path()));
        long cut = parsed.validBytes();
        for (int i = 0; i < parsed.entries().size(); i++) {
            if (parsed.entries().get(i).index() >= index) { cut = parsed.offsets().get(i); break; }
        }
        openActive(segment.path());
        active.truncate(cut);
        activeSize = cut;
        dirty.add(segment.path());
        while (lastIndex() >= index) entries.remove(entries.size() - 1);
    }

    @Override
    public void compactThrough(long index) throws IOException {
        if (index <= baseIndex) return;
        long lastBefore = lastIndex();
        int drop = (int) Math.min(entries.size(), index - baseIndex);
        entries.subList(0, drop).clear();
        baseIndex = index;
        // A segment can go once every record in it is covered: its last index is the next
        // segment's first index minus one (or the log's last index for the newest segment).
        for (int i = segments.size() - 1; i >= 0; i--) {
            long segmentLast = i + 1 < segments.size() ? segments.get(i + 1).firstIndex() - 1 : lastBefore;
            if (segmentLast <= index) deleteSegment(i);
        }
    }

    @Override
    public void flush() throws IOException {
        for (Path path : List.copyOf(dirty)) {
            long started = System.nanoTime();
            if (active != null && path.equals(segments.getLast().path())) {
                active.force(true);
            } else if (Files.exists(path)) {
                try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); }
            } else {
                continue;
            }
            recordFsync(System.nanoTime() - started);
        }
        dirty.clear();
    }

    @Override
    public void close() throws IOException {
        flush();
        if (active != null) active.close();
        active = null;
    }

    @Override public long fsyncCount() { return fsyncCount; }
    @Override public long fsyncNanos() { return fsyncNanos; }

    public long lastIndex() { return baseIndex + entries.size(); }

    // ---------------- recovery ----------------

    private void recover() throws IOException {
        snapshot = snapshotFile.readLatest();
        List<Segment> found = listSegments();
        List<LogEntry> recovered = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            Segment segment = found.get(i);
            Parsed parsed = parse(Files.readAllBytes(segment.path()));
            boolean corrupt = parsed.validBytes() < Files.size(segment.path());
            int good = 0;
            for (LogEntry entry : parsed.entries()) {
                boolean contiguous = recovered.isEmpty()
                        ? entry.index() == segment.firstIndex()
                        : entry.index() == recovered.getLast().index() + 1;
                if (!contiguous) { corrupt = true; break; }
                recovered.add(entry);
                good++;
            }
            segments.add(segment);
            if (corrupt) {
                long cut = good < parsed.offsets().size() ? parsed.offsets().get(good) : parsed.validBytes();
                try (FileChannel channel = FileChannel.open(segment.path(), StandardOpenOption.WRITE)) {
                    channel.truncate(cut);
                    channel.force(true);
                }
                for (int later = i + 1; later < found.size(); later++) Files.deleteIfExists(found.get(later).path());
                break;
            }
        }

        // Keep only entries the snapshot does not cover, and only if they continue right after it.
        baseIndex = snapshot.index();
        if (snapshot.index() == 0 && !recovered.isEmpty()) baseIndex = recovered.getFirst().index() - 1;
        for (LogEntry entry : recovered) {
            if (entry.index() <= baseIndex) continue;
            if (entry.index() != baseIndex + entries.size() + 1) break;
            entries.add(entry);
        }
        long diskLast = recovered.isEmpty() ? 0 : recovered.getLast().index();
        if (diskLast != lastIndex() && !entries.isEmpty()) {
            throw new IOException("WAL records after the snapshot are not contiguous");
        }
        if (entries.isEmpty()) {
            // Everything on disk is covered by the snapshot: finish the interrupted compaction.
            for (int i = segments.size() - 1; i >= 0; i--) deleteSegment(i);
        } else {
            compactThroughOnDisk(baseIndex);
            openActive(segments.getLast().path());
        }
    }

    private void compactThroughOnDisk(long index) throws IOException {
        for (int i = segments.size() - 2; i >= 0; i--) {
            if (segments.get(i + 1).firstIndex() - 1 <= index) deleteSegment(i);
        }
    }

    // ---------------- segment files ----------------

    private void roll(long firstIndex) throws IOException {
        if (active != null) active.close();   // still in `dirty` if unflushed
        active = null;
        Segment segment = new Segment(firstIndex, walDirectory.resolve(String.format("%020d.seg", firstIndex)));
        segments.add(segment);
        openActive(segment.path());
    }

    private void openActive(Path path) throws IOException {
        if (active != null && path.equals(segments.getLast().path())) return;
        if (active != null) active.close();
        if (!path.equals(segments.getLast().path())) throw new IllegalStateException("only the newest segment is writable");
        active = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        activeSize = active.size();
        active.position(activeSize);
    }

    private void writePending(ByteArrayOutputStream pending) throws IOException {
        if (pending.size() == 0) return;
        ByteBuffer buffer = ByteBuffer.wrap(pending.toByteArray());
        active.position(activeSize);
        while (buffer.hasRemaining()) active.write(buffer);
        activeSize += pending.size();
        dirty.add(segments.getLast().path());
        pending.reset();
    }

    private void deleteSegment(int position) throws IOException {
        Segment segment = segments.remove(position);
        if (position == segments.size() && active != null) {   // it was the newest (active) segment
            active.close();
            active = null;
            activeSize = 0;
        }
        dirty.remove(segment.path());
        Files.deleteIfExists(segment.path());
        if (active == null && !segments.isEmpty() && position == segments.size()) openActive(segments.getLast().path());
    }

    private List<Segment> listSegments() throws IOException {
        try (var paths = Files.list(walDirectory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".seg"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(path -> new Segment(Long.parseLong(path.getFileName().toString().replace(".seg", "")), path))
                    .toList();
        }
    }

    private void recordFsync(long nanos) {
        fsyncCount++;
        fsyncNanos += nanos;
        fsyncTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    // ---------------- record format ----------------

    private static byte[] encodeRecord(LogEntry entry) {
        byte[] command = entry.command();
        ByteBuffer payload = ByteBuffer.allocate(PAYLOAD_FIXED_BYTES + command.length);
        payload.put((byte) 1).putLong(entry.index()).putLong(entry.term()).putInt(command.length).put(command);
        CRC32 crc = new CRC32();
        crc.update(payload.array());
        ByteBuffer record = ByteBuffer.allocate(RECORD_HEADER_BYTES + payload.capacity());
        record.putInt(payload.capacity()).putInt((int) crc.getValue()).put(payload.array());
        return record.array();
    }

    /** Parses records until the end or the first incomplete/corrupt one. */
    private static Parsed parse(byte[] bytes) {
        List<LogEntry> parsed = new ArrayList<>();
        List<Long> offsets = new ArrayList<>();
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.remaining() >= RECORD_HEADER_BYTES) {
            int start = buffer.position();
            int length = buffer.getInt();
            int expectedCrc = buffer.getInt();
            if (length < PAYLOAD_FIXED_BYTES || length > MAX_RECORD_BYTES || length > buffer.remaining()) {
                buffer.position(start);
                break;
            }
            CRC32 crc = new CRC32();
            crc.update(bytes, buffer.position(), length);
            if ((int) crc.getValue() != expectedCrc) { buffer.position(start); break; }
            byte type = buffer.get();
            long index = buffer.getLong();
            long term = buffer.getLong();
            int commandLength = buffer.getInt();
            if (type != 1 || index < 1 || term < 0 || commandLength != length - PAYLOAD_FIXED_BYTES) {
                buffer.position(start);
                break;
            }
            byte[] command = new byte[commandLength];
            buffer.get(command);
            parsed.add(new LogEntry(index, term, command));
            offsets.add((long) start);
        }
        return new Parsed(parsed, offsets, buffer.position());
    }
}
