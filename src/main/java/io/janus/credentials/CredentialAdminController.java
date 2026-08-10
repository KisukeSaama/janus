package io.janus.credentials;

import io.janus.audit.AuditService;
import io.janus.openbao.OpenBaoClient;
import io.janus.grants.GrantRepository;
import io.janus.providers.*;
import io.janus.shared.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

@RestController @RequestMapping("/api/admin/credentials")
public class CredentialAdminController {
    private final CredentialRepository repository; private final ProviderRepository providers; private final GrantRepository grants; private final OpenBaoClient bao; private final AuditService audit;
    public CredentialAdminController(CredentialRepository repository,ProviderRepository providers,GrantRepository grants,OpenBaoClient bao,AuditService audit){this.repository=repository;this.providers=providers;this.grants=grants;this.bao=bao;this.audit=audit;}
    public record Request(@NotBlank @Size(max=120) String name,@NotNull UUID providerId,@NotNull Environment environment,@NotNull AuthType authType,@Size(max=100) String headerName,@Size(min=1,max=8192) String secret,boolean enabled){}
    public record Response(UUID id,String name,UUID providerId,String providerName,Environment environment,AuthType authType,String headerName,String secretRef,boolean enabled,Instant createdAt,Instant updatedAt){}
    @GetMapping @Transactional(readOnly=true) public List<Response> list(){return repository.findAll().stream().map(this::response).toList();}
    @PostMapping @Transactional public Response create(@Valid @RequestBody Request r){if(r.secret()==null||r.secret().isBlank())throw new IllegalArgumentException("Secret is required when creating a credential");var provider=provider(r.providerId());check(r,provider);var c=new Credential();c.id=UUID.randomUUID();c.secretPath="janus/"+r.environment().name().toLowerCase()+"/"+provider.slug+"/"+c.id;apply(c,r,provider);bao.write(c.secretPath,r.secret());repository.save(c);log("CREDENTIAL_CREATED",c);return response(c);}
    @PutMapping("/{id}") @Transactional public Response update(@PathVariable UUID id,@Valid @RequestBody Request r){var c=get(id);if(c.environment!=r.environment()||!c.provider.id.equals(r.providerId()))throw new IllegalArgumentException("Credential provider and environment cannot be changed; create a separate credential");var provider=provider(r.providerId());check(r,provider);apply(c,r,provider);if(r.secret()!=null&&!r.secret().isBlank())bao.write(c.secretPath,r.secret());log("CREDENTIAL_UPDATED",c);return response(c);}
    @DeleteMapping("/{id}") @Transactional public void delete(@PathVariable UUID id){var c=get(id);if(grants.existsByCredentialId(id))throw new IllegalStateException("Credential is still used by an access grant");bao.delete(c.secretPath);repository.delete(c);log("CREDENTIAL_DELETED",c);}
    private void check(Request r,Provider p){if(p.environment!=r.environment())throw new IllegalArgumentException("Credential and provider environments must match");if(r.authType()==AuthType.API_KEY_HEADER&&(r.headerName()==null||!r.headerName().matches("[A-Za-z0-9-]{1,100}")))throw new IllegalArgumentException("A valid header name is required for API key authentication");}
    private void apply(Credential c,Request r,Provider p){c.name=r.name().trim();c.provider=p;c.environment=r.environment();c.authType=r.authType();c.headerName=r.authType()==AuthType.API_KEY_HEADER?r.headerName():null;c.enabled=r.enabled();}
    private Credential get(UUID id){return repository.findById(id).orElseThrow(()->new NotFoundException("Credential not found"));} private Provider provider(UUID id){return providers.findById(id).orElseThrow(()->new NotFoundException("Provider not found"));}
    private Response response(Credential c){return new Response(c.id,c.name,c.provider.id,c.provider.name,c.environment,c.authType,c.headerName,"openbao://"+c.secretPath,c.enabled,c.createdAt,c.updatedAt);}
    private void log(String action,Credential c){audit.record("ADMIN","admin",action,"SUCCESS",c.provider.id,null,null,null,c.id.toString(),UUID.randomUUID().toString());}
}
