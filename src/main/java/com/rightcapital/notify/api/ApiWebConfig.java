package com.rightcapital.notify.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rightcapital.notify.config.NotificationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.Map;

/**
 * 接入层装配：把 ApiKeyAuthFilter 注册到 /v1/* 路径，最先于业务执行。
 */
@Configuration
public class ApiWebConfig {

    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilter(NotificationProperties properties,
                                                                     ObjectMapper objectMapper) {
        Map<String, String> callerKeys = properties.api().callers();
        FilterRegistrationBean<ApiKeyAuthFilter> registration =
                new FilterRegistrationBean<>(new ApiKeyAuthFilter(callerKeys, objectMapper));
        registration.addUrlPatterns("/v1/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
