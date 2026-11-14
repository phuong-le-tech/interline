package app.interline.app;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
class WebConfiguration {
    @Bean FilterRegistrationBean<ApiSecurityFilter> apiSecurity(InterlineProperties properties) {
        FilterRegistrationBean<ApiSecurityFilter> registration =
                new FilterRegistrationBean<>(new ApiSecurityFilter(properties.token(), properties.allowedOrigins()));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
