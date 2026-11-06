package example.regtests;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AutoConfigurationTest {

    @TempDir
    Path recordings;

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RegtestsAutoConfiguration.class, DataSourceAutoConfiguration.class,
                    TaskExecutionAutoConfiguration.class, RestTemplateAutoConfiguration.class,
                    RestClientAutoConfiguration.class, WebClientAutoConfiguration.class))
            .withPropertyValues("spring.datasource.url=jdbc:h2:tcp://127.0.0.1:1/never");

    @AfterEach
    void cleanUp() {
        OutboundHttp.activate(null);
    }

    @Test
    void offIsTheDefaultAndCreatesNothing() {
        runner.withBean(RestTemplate.class, RestTemplate::new).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(Regtests.class);
            assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
            assertThat(context).doesNotHaveBean(HttpCaptureInterceptor.class);
            assertThat(context.getBean(DataSource.class).getClass().getName()).contains("Hikari");
            assertThat(context.getBean(RestTemplate.class).getInterceptors()).isEmpty();
            assertThat(OutboundHttp.isOn()).isFalse();
        });
    }

    @Test
    void aBadModeStopsTheServiceWithAClearMessage() {
        runner.withPropertyValues("regtests.mode=sometimes").run(context ->
                assertThat(context).getFailure().rootCause().hasMessageContaining("off, record or replay"));
    }

    @Test
    void replaySwapsTheDataSourceAndStartsNoConnection() {
        replay().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(DataSource.class).toString()).isEqualTo("ReplayDataSource");
            assertThat(context.getBean(DataSource.class).getConnection().isClosed()).isFalse();
        });
    }

    @Test
    void aRestTemplateBeanBuiltWithNewIsHooked() {
        replay().withBean(RestTemplate.class, RestTemplate::new).run(context ->
                assertThat(context.getBean(RestTemplate.class).getInterceptors())
                        .containsExactly(context.getBean(HttpCaptureInterceptor.class)));
    }

    @Test
    void aRestTemplateBeanFromTheBuilderIsHookedOnce() {
        replay().run(context -> {
            RestTemplate restTemplate = context.getBean(RestTemplateBuilder.class).build();
            assertThat(restTemplate.getInterceptors()).containsExactly(context.getBean(HttpCaptureInterceptor.class));
        });
    }

    private WebApplicationContextRunner replay() {
        return runner.withPropertyValues("regtests.mode=replay", "regtests.recordings=" + recordings);
    }
}
