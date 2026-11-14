package app.interline.domain;

import java.security.SecureRandom;
import java.util.UUID;

/** Time-ordered ids (RFC 9562 version 7) so meeting lists sort by creation without an extra column. */
public final class UuidV7 {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long VERSION_7 = 0x7000L;
    private static final long VARIANT_RFC = 0x8000_0000_0000_0000L;

    private UuidV7() { }

    public static String next(long epochMillis) {
        long high = (epochMillis << 16) | VERSION_7 | (RANDOM.nextLong() & 0x0FFFL);
        long low = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | VARIANT_RFC;
        return new UUID(high, low).toString();
    }
}
