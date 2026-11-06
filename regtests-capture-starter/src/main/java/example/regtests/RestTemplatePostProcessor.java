package example.regtests;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.web.client.RestTemplate;

/** Catches RestTemplate beans built with new, which the builder's customizer never sees. */
final class RestTemplatePostProcessor implements BeanPostProcessor {

    private final ObjectProvider<HttpCaptureInterceptor> interceptor;

    RestTemplatePostProcessor(ObjectProvider<HttpCaptureInterceptor> interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof RestTemplate restTemplate) {
            HttpCaptureInterceptor hook = interceptor.getObject();
            if (!restTemplate.getInterceptors().contains(hook)) {
                restTemplate.getInterceptors().add(hook);
            }
        }
        return bean;
    }
}
