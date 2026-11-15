package jraft.storage;

import jraft.raftcore.Snapshot;

import java.io.IOException;
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
 * snapshots/snap-&lt;lastIndex&gt;-&lt;lastTerm&gt;.bin = [magic:4][version:1][index:8][term:8][length:4][data][crc32:4]
 * (ARCHITECTURE.md section 2). Written to a .tmp file, forced, then atomically renamed, so a crash
 * leaves either the old snapshot or the complete new one. Older snapshots are deleted only after
 * the new one is durable. Loading picks the newest file whose CRC is valid.
 */
final class SnapshotFile {
    private static final int MAGIC = 0x4A534E50; // JSNP
    private static final byte VERSION = 1;
    private static final int HEADER_BYTES = Integer.BYTES + 1 + Long.BYTES + Long.BYTES + Integer.BYTES;

    private final Path directory;

    SnapshotFile(Path directory) { this.directory = directory; }

    /** Returns the time spent in fsync. */
    long write(Snapshot snapshot) throws IOException {
        Files.createDirectories(directory);
        byte[] data = snapshot.data();
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + data.length + Integer.BYTES);
        buffer.putInt(MAGIC).put(VERSION).putLong(snapshot.index()).putLong(snapshot.term()).putInt(data.length).put(data);
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
            Snapshot snapshot = tryRead(files.get(i));
            if (snapshot != null) return snapshot;
        }
        return Snapshot.EMPTY;
    }

    private static Snapshot tryRead(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < HEADER_BYTES + Integer.BYTES) return null;
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (buffer.getInt() != MAGIC || buffer.get() != VERSION) return null;
        long index = buffer.getLong();
        long term = buffer.getLong();
        int length = buffer.getInt();
        if (length < 0 || length != bytes.length - HEADER_BYTES - Integer.BYTES) return null;
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, HEADER_BYTES + length);
        buffer.position(HEADER_BYTES + length);
        if ((int) crc.getValue() != buffer.getInt()) return null;
        byte[] data = new byte[length];
        System.arraycopy(bytes, HEADER_BYTES, data, 0, length);
        return new Snapshot(index, term, data);
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
