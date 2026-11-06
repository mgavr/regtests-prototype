package example.regtests;

/** Thrown in replay when the service tries something the recording does not contain. */
public class UnexpectedCallException extends RuntimeException {

    public UnexpectedCallException(String message) {
        super(message);
    }
}
