package io.janus.gateway;

import io.janus.grants.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RouteAuthorizerTest {
    private final RouteAuthorizer authorizer=new RouteAuthorizer();
    @Test void matchesBothMethodAndRoute(){var grant=new Grant();var p=new RoutePolicy();p.httpMethod="GET";p.pathPattern="/v1/customers/**";grant.policies.add(p);assertThat(authorizer.allowed(grant,"GET","/v1/customers/42")).isTrue();assertThat(authorizer.allowed(grant,"POST","/v1/customers/42")).isFalse();assertThat(authorizer.allowed(grant,"GET","/v1/admin")).isFalse();}
}
