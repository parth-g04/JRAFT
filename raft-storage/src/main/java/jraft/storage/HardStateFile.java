package jraft.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;

/** CRC-protected hard state written using force plus atomic replacement. */
public final class HardStateFile {
    private static final int MAGIC = 0x4A524146; // JRAF
    private static final byte VERSION = 1;
    private static final int CONTENT_BYTES = Integer.BYTES + 1 + Long.BYTES + Integer.BYTES + Long.BYTES;
    private static final int FILE_BYTES = CONTENT_BYTES + Integer.BYTES;
    private final Path path;

    public HardStateFile(Path path) { this.path = path; }

    public HardState read() throws IOException {
        if (!Files.exists(path)) return HardState.empty();
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length != FILE_BYTES) throw new IOException("invalid hard state length: " + path);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        int magic = buffer.getInt();
        byte version = buffer.get();
        long term = buffer.getLong();
        int vote = buffer.getInt();
        long commit = buffer.getLong();
        int expectedCrc = buffer.getInt();
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, CONTENT_BYTES);
        if (magic != MAGIC || version != VERSION || (int) crc.getValue() != expectedCrc) {
            throw new IOException("hard state checksum or version mismatch: " + path);
        }
        try {
            return new HardState(term, vote < 0 ? null : vote, commit);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid values in hard state: " + path, invalid);
        }
    }

    public void write(HardState hardState) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        ByteBuffer contents = ByteBuffer.allocate(CONTENT_BYTES);
        contents.putInt(MAGIC).put(VERSION).putLong(hardState.currentTerm())
                .putInt(hardState.votedFor() == null ? -1 : hardState.votedFor())
                .putLong(hardState.commitIndex());
        CRC32 crc = new CRC32();
        crc.update(contents.array());
        ByteBuffer file = ByteBuffer.allocate(FILE_BYTES);
        file.put(contents.array()).putInt((int) crc.getValue()).flip();

        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            while (file.hasRemaining()) channel.write(file);
            channel.force(true);
        }
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
