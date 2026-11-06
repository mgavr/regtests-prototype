package example.regtests;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code regtests.*}. Each can come from an environment variable, so
 * {@code REGTESTS_MODE} sets {@code regtests.mode}, {@code REGTESTS_RECORDINGS} sets
 * {@code regtests.recordings} and so on.
 */
@ConfigurationProperties("regtests")
public class RegtestsProperties {

    /** off, record or replay. */
    private String mode = "off";

    /** Folder that holds one sub-folder per case. */
    private String recordings = "recordings";

    /** Optional JSON-lines file that receives one event per step. Unset means no events. */
    private String events;

    /** Mixed into every mask. Not a secret in this demo. In real use, supply your own per environment. */
    private String salt = "regtests-salt";

    /** How long to wait for @Async work to finish before a case is closed. */
    private int asyncWaitSeconds = 10;

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getRecordings() {
        return recordings;
    }

    public void setRecordings(String recordings) {
        this.recordings = recordings;
    }

    public String getEvents() {
        return events;
    }

    public void setEvents(String events) {
        this.events = events;
    }

    public String getSalt() {
        return salt;
    }

    public void setSalt(String salt) {
        this.salt = salt;
    }

    public int getAsyncWaitSeconds() {
        return asyncWaitSeconds;
    }

    public void setAsyncWaitSeconds(int asyncWaitSeconds) {
        this.asyncWaitSeconds = asyncWaitSeconds;
    }
}
