package example.regtests;

import java.time.Duration;

/** What every hook needs, in one place. {@code files} holds the recordings, {@code runs} what replay wrote down. */
record Regtests(RegtestsMode mode, Events events, Scrubber scrubber, CaseFiles files, CaseFiles runs,
                Duration asyncWait) {
}
