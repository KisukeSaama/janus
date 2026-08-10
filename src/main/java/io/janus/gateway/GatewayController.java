package io.janus.gateway;

import io.janus.audit.AuditService;
import io.janus.credentials.*;
import io.janus.grants.*;
import io.janus.openbao.OpenBaoClient;
import io.janus.providers.*;
import io.janus.security.GatewayPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.*;
import org.springframework.web.util.UriComponentsBuilder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController @RequestMapping("/gateway")
public class GatewayController {
    private static final Set<String> BLOCKED_REQUEST=Set.of("host","authorization","proxy-authorization","cookie","connection","content-length","transfer-encoding","upgrade","accept-encoding","x-janus-api-key","x-janus-application-id");
    private static final Set<String> BLOCKED_RESPONSE=Set.of("set-cookie","authorization","proxy-authorization","www-authenticate","proxy-authenticate","connection","transfer-encoding","upgrade");
    private final ProviderRepository providers; private final GrantRepository grants; private final DestinationValidator destinations; private final RouteAuthorizer authorizer; private final OpenBaoClient bao; private final WebClient web; private final AuditService audit;
    public GatewayController(ProviderRepository providers,GrantRepository grants,DestinationValidator destinations,RouteAuthorizer authorizer,OpenBaoClient bao,WebClient.Builder builder,AuditService audit,@Value("${janus.gateway.max-response-bytes:10485760}") int maxBytes){this.providers=providers;this.grants=grants;this.destinations=destinations;this.authorizer=authorizer;this.bao=bao;this.audit=audit;this.web=builder.codecs(c->c.defaultCodecs().maxInMemorySize(maxBytes)).build();}

    @RequestMapping("/{slug}/**")
    public ResponseEntity<byte[]> proxy(@PathVariable String slug,@AuthenticationPrincipal GatewayPrincipal principal,HttpServletRequest request,@RequestBody(required=false) byte[] body){
        String correlation=Optional.ofNullable(request.getHeader("X-Correlation-Id")).filter(v->v.matches("[A-Za-z0-9._-]{1,80}")).orElseGet(()->UUID.randomUUID().toString());
        Provider provider=null; String path=extractPath(request.getRequestURI(),slug);
        try {
            provider=providers.findBySlugAndEnvironmentAndEnabledTrue(slug,principal.environment()).orElseThrow(()->new Denied(HttpStatus.NOT_FOUND,"Provider is not available"));
            destinations.validate(provider.baseUrl);
            var grant=grants.findActive(principal.applicationId(),provider.id,principal.environment()).orElseThrow(()->new Denied(HttpStatus.FORBIDDEN,"No active grant for this provider"));
            if(!grant.credential.enabled)throw new Denied(HttpStatus.FORBIDDEN,"Credential is disabled");
            if(!authorizer.allowed(grant,request.getMethod(),path))throw new Denied(HttpStatus.FORBIDDEN,"Route or method is not allowed");
            String secret=bao.read(grant.credential.secretPath);
            URI target=target(provider.baseUrl,path,request.getQueryString());
            var spec=web.method(HttpMethod.valueOf(request.getMethod())).uri(target).headers(h->{copyRequestHeaders(request,h);inject(h,grant.credential,secret);h.set("X-Correlation-Id",correlation);});
            WebClient.RequestHeadersSpec<?> outbound=body!=null&&body.length>0?spec.bodyValue(body):spec;
            var entity=outbound.exchangeToMono(response->response.toEntity(byte[].class)).block();
            if(entity==null)throw new IllegalStateException("Provider returned no response");
            var headers=new HttpHeaders();entity.getHeaders().forEach((k,v)->{if(!BLOCKED_RESPONSE.contains(k.toLowerCase(Locale.ROOT)))headers.put(k,v);});headers.set("X-Janus-Correlation-Id",correlation);
            byte[] safeBody=redact(entity.getBody(),entity.getHeaders().getContentType(),secret);
            audit.record("APPLICATION",principal.applicationId().toString(),"GATEWAY_REQUEST","SUCCESS",provider.id,request.getMethod(),path,entity.getStatusCode().value(),null,correlation);
            return new ResponseEntity<>(safeBody,headers,entity.getStatusCode());
        } catch(Denied ex){audit.record("APPLICATION",principal.applicationId().toString(),"GATEWAY_REQUEST","DENIED",provider==null?null:provider.id,request.getMethod(),path,ex.status.value(),ex.getMessage(),correlation);return problem(ex.status,ex.getMessage(),correlation);}
        catch(Exception ex){audit.record("APPLICATION",principal.applicationId().toString(),"GATEWAY_REQUEST","ERROR",provider==null?null:provider.id,request.getMethod(),path,502,"Upstream request failed",correlation);return problem(HttpStatus.BAD_GATEWAY,"Upstream request failed",correlation);}
    }
    private URI target(String base,String path,String query){var builder=UriComponentsBuilder.fromUriString(base);String basePath=Optional.ofNullable(builder.build().getPath()).orElse("");builder.replacePath(basePath+path);if(query!=null)builder.query(query);return builder.build(true).toUri();}
    private String extractPath(String uri,String slug){String prefix="/gateway/"+slug;String path=uri.substring(prefix.length());if(path.isEmpty())path="/";if(!path.startsWith("/")||path.contains("%")||path.contains("..")||path.contains("\\")||path.contains("//")||path.indexOf('\0')>=0)throw new Denied(HttpStatus.BAD_REQUEST,"Unsafe gateway path");return path;}
    private void copyRequestHeaders(HttpServletRequest req,HttpHeaders out){Collections.list(req.getHeaderNames()).forEach(name->{if(!BLOCKED_REQUEST.contains(name.toLowerCase(Locale.ROOT)))out.put(name,Collections.list(req.getHeaders(name)));});}
    private void inject(HttpHeaders h,Credential c,String secret){switch(c.authType){case BEARER->h.setBearerAuth(secret);case API_KEY_HEADER->h.set(c.headerName,secret);case BASIC->h.setBasicAuth(Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)));}}
    private byte[] redact(byte[] body,MediaType type,String secret){if(body==null||type==null||(!"text".equals(type.getType())&&!type.isCompatibleWith(MediaType.APPLICATION_JSON)))return body;String encoded=Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8));return new String(body,StandardCharsets.UTF_8).replace(secret,"[REDACTED]").replace(encoded,"[REDACTED]").getBytes(StandardCharsets.UTF_8);}
    private ResponseEntity<byte[]> problem(HttpStatus status,String detail,String correlation){String json="{\"title\":\"Gateway request rejected\",\"status\":"+status.value()+",\"detail\":\""+detail.replace("\"","'")+"\",\"correlationId\":\""+correlation+"\"}";return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).header("X-Janus-Correlation-Id",correlation).body(json.getBytes(StandardCharsets.UTF_8));}
    private static class Denied extends RuntimeException{final HttpStatus status;Denied(HttpStatus status,String message){super(message);this.status=status;}}
}
