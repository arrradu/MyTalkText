package ro.talktext;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** 16 kHz, mono, 16-bit PCM WAV. 25 MB upload limit = about 13 minutes per piece; pieces are at most 90 s. */
public final class Wav {
    public static final int RATE = 16_000;

    public static byte[] wrap(byte[] pcm, int length) {
        ByteBuffer b = ByteBuffer.allocate(44 + length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + length).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes()).putInt(length).put(pcm, 0, length);
        return b.array();
    }

    private Wav() {}
}
