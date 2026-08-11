package com.ktb.chatapp.config;

import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MongoConnectionPoolConfig {

    @Bean
    MongoClientSettingsBuilderCustomizer mongoConnectionPoolCustomizer() {
        return builder -> builder.applyToConnectionPoolSettings(pool -> pool.maxSize(150));
    }
}
