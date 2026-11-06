package example.quotes;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
class QuoteController {

    private final QuoteService quotes;

    QuoteController(QuoteService quotes) {
        this.quotes = quotes;
    }

    @PostMapping("/quotes")
    QuoteResponse create(@RequestBody QuoteRequest request) {
        return quotes.createQuote(request);
    }

    @ExceptionHandler(CustomerNotFoundException.class)
    ResponseEntity<Map<String, Object>> customerNotFound(CustomerNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "customer not found", "customerId", e.customerId()));
    }
}
