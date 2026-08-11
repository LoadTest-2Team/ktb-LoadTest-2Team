package com.ktb.chatapp.config;

import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Ensures that Micrometer can bind Tomcat connector and thread-pool MBeans. */
@Configuration(proxyBeanMethods = false)
public class TomcatMetricsConfig {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatMBeanRegistryCustomizer() {
        return factory -> factory.setDisableMBeanRegistry(false);
    }
}
