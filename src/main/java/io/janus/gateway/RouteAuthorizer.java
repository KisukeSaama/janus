package io.janus.gateway;

import io.janus.grants.Grant;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

@Component
public class RouteAuthorizer {
    private final AntPathMatcher matcher=new AntPathMatcher();
    public boolean allowed(Grant grant,String method,String path){return grant.policies.stream().anyMatch(p->p.httpMethod.equals(method)&&matcher.match(p.pathPattern,path));}
}
