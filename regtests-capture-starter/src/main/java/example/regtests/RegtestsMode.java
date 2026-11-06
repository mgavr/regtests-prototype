package example.regtests;

import java.util.Locale;

/** The three modes. {@code REGTESTS_MODE} picks one and the default is {@link #OFF}. */
public enum RegtestsMode {
    OFF, RECORD, REPLAY;

    public static RegtestsMode parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("REGTESTS_MODE must be off, record or replay, not: " + value);
        }
    }

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
