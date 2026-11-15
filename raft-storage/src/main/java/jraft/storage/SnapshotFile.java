package jraft.storage;

import jraft.raftcore.Configuration;
import jraft.raftcore.Snapshot;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32;

/**
 * snapshots/snap-&lt;lastIndex&gt;-&lt;lastTerm&gt;.bin (ARCHITECTURE.md section 2):
 * <pre>
 * v2: [magic:4][version=2:1][index:8][term:8][configLength:4][config][dataLength:4][data][crc32:4]
 * v1: [magic:4][version=1:1][index:8][term:8][dataLength:4][data][crc32:4]   (read only; no config)
 * </pre>
 * configLength = -1 means "no configuration recorded". Written to a .tmp file, forced, then
 * atomically renamed, so a crash leaves either the old snapshot or the complete new one. Older
 * snapshots are deleted only after the new one is durable. Loading picks the newest valid file.
 */
final class SnapshotFile {
    private static final int MAGIC = 0x4A534E50; // JSNP
    private static final byte VERSION_1 = 1;
    private static final byte VERSION_2 = 2;

    private final Path directory;

    SnapshotFile(Path directory) { this.directory = directory; }

    /** Returns the time spent in fsync. */
    long write(Snapshot snapshot) throws IOException {
        Files.createDirectories(directory);
        byte[] data = snapshot.data();
        byte[] config = snapshot.configuration() == null ? null : snapshot.configuration().encode();
        int configBytes = config == null ? 0 : config.length;
        ByteBuffer buffer = ByteBuffer.allocate(4 + 1 + 8 + 8 + 4 + configBytes + 4 + data.length + 4);
        buffer.putInt(MAGIC).put(VERSION_2).putLong(snapshot.index()).putLong(snapshot.term());
        buffer.putInt(config == null ? -1 : config.length);
        if (config != null) buffer.put(config);
        buffer.putInt(data.length).put(data);
        CRC32 crc = new CRC32();
        crc.update(buffer.array(), 0, buffer.position());
        buffer.putInt((int) crc.getValue()).flip();

        Path target = directory.resolve("snap-" + String.format("%020d", snapshot.index()) + "-" + snapshot.term() + ".bin");
        Path temporary = directory.resolve(target.getFileName() + ".tmp");
        long fsyncNanos;
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            while (buffer.hasRemaining()) channel.write(buffer);
            long started = System.nanoTime();
            channel.force(true);
            fsyncNanos = System.nanoTime() - started;
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        for (Path older : snapshotFiles()) {
            if (!older.equals(target)) Files.deleteIfExists(older);
        }
        return fsyncNanos;
    }

    Snapshot readLatest() throws IOException {
        if (!Files.isDirectory(directory)) return Snapshot.EMPTY;
        List<Path> files = snapshotFiles();
        for (int i = files.size() - 1; i >= 0; i--) {
            Snapshot snapshot = tryRead(Files.readAllBytes(files.get(i)));
            if (snapshot != null) return snapshot;
        }
        return Snapshot.EMPTY;
    }

    /** Parses one snapshot file; null if it is truncated, corrupt or of an unknown version. */
    static Snapshot tryRead(byte[] bytes) {
        try {
            if (bytes.length < Integer.BYTES) return null;
            CRC32 crc = new CRC32();
            crc.update(bytes, 0, bytes.length - Integer.BYTES);
            if ((int) crc.getValue() != ByteBuffer.wrap(bytes, bytes.length - Integer.BYTES, Integer.BYTES).getInt()) {
                return null;
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, bytes.length - Integer.BYTES);
            if (buffer.getInt() != MAGIC) return null;
            byte version = buffer.get();
            long index = buffer.getLong();
            long term = buffer.getLong();
            Configuration configuration = null;
            if (version == VERSION_2) {
                int configLength = buffer.getInt();
                if (configLength >= 0) {
                    byte[] config = new byte[configLength];
                    buffer.get(config);
                    configuration = Configuration.decode(config);
                }
            } else if (version != VERSION_1) {
                return null;
            }
            int length = buffer.getInt();
            if (length != buffer.remaining()) return null;
            byte[] data = new byte[length];
            buffer.get(data);
            return new Snapshot(index, term, configuration, data);
        } catch (BufferUnderflowException | IllegalArgumentException | NegativeArraySizeException corrupt) {
            return null;
        }
    }

    /** Complete snapshot files (no .tmp), oldest first. Zero-padded names sort by index. */
    private List<Path> snapshotFiles() throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("snap-") && name.endsWith(".bin");
                    })
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
    }
}
