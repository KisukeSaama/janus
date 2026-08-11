package io.janus.openbao;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("janus.openbao")
public record OpenBaoProperties(String address, String token, String kvMount) {}
