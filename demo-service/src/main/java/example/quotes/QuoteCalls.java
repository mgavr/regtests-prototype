package example.quotes;

import java.math.BigDecimal;
import java.time.Instant;

/** Parameter objects for the stored procedures. MyBatis writes OUT values straight into these fields. */
final class QuoteCalls {

    private QuoteCalls() {
    }

    static final class CustomerLookup {
        long customerId;
        String fullName;
        String phone;
        String region;
        String tier;
        int found;

        CustomerLookup(long customerId) {
            this.customerId = customerId;
        }
    }

    static final class DiscountLookup {
        String tier;
        int ruleCount;

        DiscountLookup(String tier) {
            this.tier = tier;
        }
    }

    static final class DiscountRule {
        String rule;
        BigDecimal discount;
    }

    static final class NewQuote {
        String id;
        String quoteNumber;
        long customerId;
        String product;
        BigDecimal coverAmount;
        BigDecimal premium;
        String contactPhone;
        String riskReference;
        Instant createdAt;
        int rowsInserted;
    }
}
