package example.quotes;

import example.quotes.PartnerClient.Fee;
import example.quotes.PartnerClient.Rate;
import example.quotes.PartnerClient.RiskRequest;
import example.quotes.PartnerClient.RiskScore;
import example.quotes.QuoteCalls.CustomerLookup;
import example.quotes.QuoteCalls.DiscountLookup;
import example.quotes.QuoteCalls.DiscountRule;
import example.quotes.QuoteCalls.NewQuote;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** The business logic: this code runs for real in every mode. Only its edges are replaced. */
@Service
class QuoteService {

    private final QuoteMapper mapper;
    private final PartnerClient partner;
    private final AuditWriter audit;
    private final boolean demoBreak;

    QuoteService(QuoteMapper mapper, PartnerClient partner, AuditWriter audit,
                 @Value("${demo.break}") boolean demoBreak) {
        this.mapper = mapper;
        this.partner = partner;
        this.audit = audit;
        this.demoBreak = demoBreak;
    }

    QuoteResponse createQuote(QuoteRequest request) {
        CustomerLookup customer = new CustomerLookup(request.customerId());
        mapper.getCustomer(customer);
        if (customer.found == 0) {
            throw new CustomerNotFoundException(request.customerId());
        }

        DiscountLookup lookup = new DiscountLookup(customer.tier);
        BigDecimal discount = mapper.getDiscountRules(lookup).stream()
                .map((DiscountRule rule) -> rule.discount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Rate rate = partner.baseRate(request.product(), customer.region);
        Fee fee = partner.policyFee(request.product());
        RiskScore risk = partner.riskScore(new RiskRequest(customer.customerId, customer.fullName, customer.region, now));

        NewQuote quote = new NewQuote();
        quote.id = UUID.randomUUID().toString();
        quote.quoteNumber = "Q-" + now.toEpochMilli();
        quote.customerId = customer.customerId;
        quote.product = request.product();
        quote.coverAmount = request.coverAmount();
        quote.premium = price(request.coverAmount(), rate.baseRate(), risk.riskFactor(), discount, fee.policyFee());
        quote.contactPhone = request.contactPhone();
        quote.riskReference = risk.reference();
        quote.createdAt = now;
        mapper.createQuote(quote);
        audit.quoteIssued(quote.id, quote.premium);

        return new QuoteResponse(quote.id, quote.quoteNumber, customer.fullName, request.product(), quote.premium,
                "USD", risk.reference(), now);
    }

    private BigDecimal price(BigDecimal cover, BigDecimal baseRate, BigDecimal riskFactor, BigDecimal discount,
                             BigDecimal fee) {
        // REG-25: THE DELIBERATE REGRESSION. With DEMO_BREAK=true this one rule adds the discount instead of taking it off.
        BigDecimal multiplier = demoBreak ? BigDecimal.ONE.add(discount) : BigDecimal.ONE.subtract(discount);
        return cover.multiply(baseRate).multiply(riskFactor).multiply(multiplier).add(fee)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
