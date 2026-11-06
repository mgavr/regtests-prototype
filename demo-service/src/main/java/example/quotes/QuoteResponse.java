package example.quotes;

import java.math.BigDecimal;
import java.time.Instant;

record QuoteResponse(String quoteId, String quoteNumber, String customerName, String product, BigDecimal premium,
                     String currency, String riskReference, Instant createdAt) {
}
