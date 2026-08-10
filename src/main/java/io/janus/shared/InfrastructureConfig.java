package io.janus.shared;

import io.janus.openbao.OpenBaoProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OpenBaoProperties.class)
public class InfrastructureConfig { }
