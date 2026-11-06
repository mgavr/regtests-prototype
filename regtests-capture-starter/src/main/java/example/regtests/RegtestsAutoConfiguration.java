package example.regtests;

import jakarta.servlet.Filter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.boot.web.codec.CodecCustomizer;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Path;
import java.time.Duration;

/** The switchboard. Add the starter to a service and this class decides what happens. */
// REG-1: one setting decides everything. REGTESTS_MODE=off (the default) fails the condition below, so not one bean is created.
@AutoConfiguration
@Conditional(RegtestsAutoConfiguration.ModeIsOn.class)
@EnableConfigurationProperties(RegtestsProperties.class)
public class RegtestsAutoConfiguration {

    static final class ModeIsOn extends SpringBootCondition {
        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            RegtestsMode mode = RegtestsMode.parse(context.getEnvironment().getProperty("regtests.mode", "off"));
            ConditionMessage.Builder message = ConditionMessage.forCondition("REGTESTS_MODE");
            return mode == RegtestsMode.OFF
                    ? ConditionOutcome.noMatch(message.because("is off"))
                    : ConditionOutcome.match(message.because("is " + mode.label()));
        }
    }

    @Bean
    Regtests regtests(RegtestsProperties properties) {
        RegtestsMode mode = RegtestsMode.parse(properties.getMode());
        ServiceConfig config = ServiceConfig.load(Path.of(properties.getRecordings()));
        Events events = new Events(properties.getEvents());
        events.emit("REG-1", RegtestsAutoConfiguration.class, "Capture library switched on: mode " + mode.label(),
                "REGTESTS_MODE=" + mode.label() + ". With off, none of this exists.");
        Regtests regtests = new Regtests(mode, events, new Scrubber(properties.getSalt(), config), new Comparer(config),
                new CaseFiles(properties.getRecordings()), Duration.ofSeconds(properties.getAsyncWaitSeconds()));
        OutboundHttp.activate(regtests);
        return regtests;
    }

    @Bean
    TaskDecorator regtestsTaskDecorator(Regtests regtests) {
        return new RequestIdTaskDecorator(regtests);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Filter.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class Inbound {
        @Bean
        FilterRegistrationBean<RegtestsFilter> regtestsFilter(Regtests regtests) {
            FilterRegistrationBean<RegtestsFilter> registration = new FilterRegistrationBean<>(new RegtestsFilter(regtests));
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(DelegatingDataSource.class)
    static class Jdbc {
        @Bean
        static DataSourcePostProcessor regtestsDataSourcePostProcessor(ObjectProvider<Regtests> regtests) {
            return new DataSourcePostProcessor(regtests);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RestTemplate.class)
    static class BlockingHttp {
        @Bean
        HttpCaptureInterceptor regtestsHttpInterceptor(Regtests regtests) {
            return new HttpCaptureInterceptor();
        }

        @Bean
        static RestTemplatePostProcessor regtestsRestTemplatePostProcessor(ObjectProvider<HttpCaptureInterceptor> interceptor) {
            return new RestTemplatePostProcessor(interceptor);
        }

        @Bean
        RestTemplateCustomizer regtestsRestTemplateCustomizer(HttpCaptureInterceptor interceptor) {
            return restTemplate -> restTemplate.getInterceptors().add(interceptor);
        }

        @Bean
        RestClientCustomizer regtestsRestClientCustomizer(HttpCaptureInterceptor interceptor) {
            return builder -> builder.requestInterceptor(interceptor);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(WebClient.class)
    static class ReactiveHttp {
        @Bean
        WebClientCustomizer regtestsWebClientCustomizer(Regtests regtests, ObjectProvider<CodecCustomizer> codecs) {
            // The same codecs as the application's own WebClient, so bodies serialize identically.
            ExchangeStrategies strategies = ExchangeStrategies.builder()
                    .codecs(configurer -> codecs.orderedStream().forEach(codec -> codec.customize(configurer)))
                    .build();
            return builder -> builder.filter(new WebClientCaptureFilter(regtests, strategies));
        }
    }
}
