package example.quotes;

import example.quotes.QuoteCalls.CustomerLookup;
import example.quotes.QuoteCalls.DiscountLookup;
import example.quotes.QuoteCalls.DiscountRule;
import example.quotes.QuoteCalls.NewQuote;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** Stored procedures, called through MyBatis. See mappers/QuoteMapper.xml. */
@Mapper
interface QuoteMapper {

    void getCustomer(CustomerLookup lookup);

    List<DiscountRule> getDiscountRules(DiscountLookup lookup);

    void createQuote(NewQuote quote);
}
