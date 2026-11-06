package example.quotes;

import java.math.BigDecimal;

record QuoteRequest(long customerId, String product, BigDecimal coverAmount, String contactPhone) {
}
