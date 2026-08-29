package com.wealth.gateway.presence;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DemoPresenceProperties.class)
public class DemoPresenceConfig {
}
