package example.quotes;

class CustomerNotFoundException extends RuntimeException {

    private final long customerId;

    CustomerNotFoundException(long customerId) {
        super("customer not found: " + customerId);
        this.customerId = customerId;
    }

    long customerId() {
        return customerId;
    }
}
