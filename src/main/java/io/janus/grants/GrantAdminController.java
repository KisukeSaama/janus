package io.janus.grants;

import io.janus.applications.*;
import io.janus.audit.AuditService;
import io.janus.credentials.*;
import io.janus.providers.*;
import io.janus.shared.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

@RestController @RequestMapping("/api/admin/grants")
public class GrantAdminController {
    private final GrantRepository repository; private final ApplicationRepository applications; private final ProviderRepository providers; private final CredentialRepository credentials; private final AuditService audit;
    public GrantAdminController(GrantRepository repository,ApplicationRepository applications,ProviderRepository providers,CredentialRepository credentials,AuditService audit){this.repository=repository;this.applications=applications;this.providers=providers;this.credentials=credentials;this.audit=audit;}
    public record PolicyRequest(@NotBlank @Pattern(regexp="GET|POST|PUT|PATCH|DELETE|HEAD") String method,@NotBlank @Pattern(regexp="/[^?#]*") @Size(max=300) String pathPattern){}
    public record Request(@NotNull UUID applicationId,@NotNull UUID providerId,@NotNull UUID credentialId,@NotNull Environment environment,boolean enabled,@NotEmpty List<@Valid PolicyRequest> policies){}
    public record PolicyResponse(UUID id,String method,String pathPattern){}
    public record Response(UUID id,UUID applicationId,String applicationName,UUID providerId,String providerName,UUID credentialId,String credentialName,Environment environment,boolean enabled,List<PolicyResponse> policies,Instant createdAt,Instant updatedAt){}
    @GetMapping @Transactional(readOnly=true) public List<Response> list(){return repository.findAllWithPolicies().stream().map(this::response).toList();}
    @PostMapping @Transactional public Response create(@Valid @RequestBody Request r){var g=new Grant();apply(g,r);repository.save(g);log("GRANT_CREATED",g);return response(g);}
    @PutMapping("/{id}") @Transactional public Response update(@PathVariable UUID id,@Valid @RequestBody Request r){var g=get(id);g.policies.clear();apply(g,r);log("GRANT_UPDATED",g);return response(g);}
    @DeleteMapping("/{id}") @Transactional public void delete(@PathVariable UUID id){var g=get(id);repository.delete(g);log("GRANT_DELETED",g);}
    private void apply(Grant g,Request r){var app=applications.findById(r.applicationId()).orElseThrow(()->new NotFoundException("Application not found"));var provider=providers.findById(r.providerId()).orElseThrow(()->new NotFoundException("Provider not found"));var credential=credentials.findById(r.credentialId()).orElseThrow(()->new NotFoundException("Credential not found"));if(app.environment!=r.environment()||provider.environment!=r.environment()||credential.environment!=r.environment())throw new IllegalArgumentException("Application, provider, credential, and grant environments must match");if(!credential.provider.id.equals(provider.id))throw new IllegalArgumentException("Credential belongs to a different provider");g.application=app;g.provider=provider;g.credential=credential;g.environment=r.environment();g.enabled=r.enabled();for(var p:r.policies()){if(p.pathPattern().contains("..")||p.pathPattern().contains("\\"))throw new IllegalArgumentException("Unsafe route pattern");var policy=new RoutePolicy();policy.grant=g;policy.httpMethod=p.method();policy.pathPattern=p.pathPattern();g.policies.add(policy);}}
    private Grant get(UUID id){return repository.findById(id).orElseThrow(()->new NotFoundException("Grant not found"));}
    private Response response(Grant g){return new Response(g.id,g.application.id,g.application.name,g.provider.id,g.provider.name,g.credential.id,g.credential.name,g.environment,g.enabled,g.policies.stream().sorted(Comparator.comparing(p->p.pathPattern)).map(p->new PolicyResponse(p.id,p.httpMethod,p.pathPattern)).toList(),g.createdAt,g.updatedAt);}
    private void log(String action,Grant g){audit.record("ADMIN","admin",action,"SUCCESS",g.provider.id,null,null,null,g.id.toString(),UUID.randomUUID().toString());}
}
