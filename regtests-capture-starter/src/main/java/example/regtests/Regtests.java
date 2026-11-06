package example.regtests;

import java.time.Duration;

/** What every hook needs, in one place. */
record Regtests(RegtestsMode mode, Events events, Scrubber scrubber, Comparer comparer, CaseFiles files,
                Duration asyncWait) {
}
