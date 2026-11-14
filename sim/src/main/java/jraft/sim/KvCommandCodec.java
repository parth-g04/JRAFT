package jraft.sim;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Small deterministic command encoding for simulated log entries. */
public final class KvCommandCodec {
    private KvCommandCodec() { }

    public static byte[] encode(KvCommand command) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeLong(command.clientId());
            out.writeLong(command.sequence());
            out.writeByte(command.operation().ordinal());
            writeString(out, command.key());
            writeString(out, command.value() == null ? "" : command.value());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static KvCommand decode(byte[] bytes) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            long clientId = in.readLong();
            long sequence = in.readLong();
            int ordinal = in.readUnsignedByte();
            if (ordinal >= KvCommand.Operation.values().length) throw new IOException("unknown operation");
            String key = readString(in);
            String value = readString(in);
            if (in.available() != 0) throw new IOException("trailing command bytes");
            return new KvCommand(clientId, sequence, KvCommand.Operation.values()[ordinal], key, value);
        } catch (IOException malformed) {
            throw new IllegalArgumentException("invalid KV command", malformed);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 1_000_000 || length > in.available()) throw new IOException("invalid string length");
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }
}
