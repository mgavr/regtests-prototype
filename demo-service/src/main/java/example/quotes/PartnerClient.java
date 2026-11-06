package example.quotes;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Instant;

/** Talks to the partner three ways on purpose, so every kind of Spring client is exercised. */
@Component
class PartnerClient {

    record Rate(String product, BigDecimal baseRate) {
    }

    record Fee(BigDecimal policyFee) {
    }

    record RiskRequest(long customerId, String fullName, String region, Instant requestedAt) {
    }

    record RiskScore(BigDecimal riskFactor, String reference) {
    }

    private final RestTemplate restTemplate;
    private final RestClient restClient;
    private final WebClient webClient;
    private final String partnerUrl;
    private final boolean unhookedClient;

    PartnerClient(RestTemplate restTemplate, RestClient restClient, WebClient webClient,
                  @Value("${partner.url}") String partnerUrl, @Value("${demo.unhooked-client}") boolean unhookedClient) {
        this.restTemplate = restTemplate;
        this.restClient = restClient;
        this.webClient = webClient;
        this.partnerUrl = partnerUrl;
        this.unhookedClient = unhookedClient;
    }

    Rate baseRate(String product, String region) {
        return restTemplate.getForObject(partnerUrl + "/rates/{product}?region={region}", Rate.class, product, region);
    }

    Fee policyFee(String product) {
        if (unhookedClient) {
            // Demo only: a client built with new inside a method, which nothing can hook. See "What a team has to do" in the README.
            return new RestTemplate().getForObject(partnerUrl + "/fees/{product}", Fee.class, product);
        }
        return restClient.get().uri(partnerUrl + "/fees/{product}", product).retrieve().body(Fee.class);
    }

    RiskScore riskScore(RiskRequest request) {
        return webClient.post()
                .uri(partnerUrl + "/risk-scores")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(RiskScore.class)
                .block();
    }
}
