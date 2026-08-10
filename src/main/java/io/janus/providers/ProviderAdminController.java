package io.janus.providers;

import io.janus.audit.AuditService;
import io.janus.shared.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

@RestController @RequestMapping("/api/admin/providers")
public class ProviderAdminController {
    private final ProviderRepository repository; private final DestinationValidator validator; private final AuditService audit;
    public ProviderAdminController(ProviderRepository repository,DestinationValidator validator,AuditService audit){this.repository=repository;this.validator=validator;this.audit=audit;}
    public record Request(@NotBlank @Size(max=120) String name,@NotBlank @Pattern(regexp="[a-z0-9][a-z0-9-]{1,78}[a-z0-9]") String slug,@NotBlank String baseUrl,@NotNull Environment environment,boolean enabled){}
    public record Response(UUID id,String name,String slug,String baseUrl,Environment environment,boolean enabled,Instant createdAt,Instant updatedAt){}
    @GetMapping @Transactional(readOnly=true) public List<Response> list(){return repository.findAll().stream().map(this::response).toList();}
    @PostMapping @Transactional public Response create(@Valid @RequestBody Request r){if(repository.existsBySlugAndEnvironment(r.slug(),r.environment()))throw new IllegalArgumentException("Provider slug already exists in this environment");var p=new Provider();apply(p,r);repository.save(p);log("PROVIDER_CREATED",p);return response(p);}
    @PutMapping("/{id}") @Transactional public Response update(@PathVariable UUID id,@Valid @RequestBody Request r){var p=get(id);if(p.environment!=r.environment())throw new IllegalArgumentException("Provider environment cannot be changed; create a separate provider");if(!p.slug.equals(r.slug())&&repository.existsBySlugAndEnvironment(r.slug(),r.environment()))throw new IllegalArgumentException("Provider slug already exists in this environment");apply(p,r);log("PROVIDER_UPDATED",p);return response(p);}
    @DeleteMapping("/{id}") @Transactional public void delete(@PathVariable UUID id){var p=get(id);repository.delete(p);log("PROVIDER_DELETED",p);}
    private Provider get(UUID id){return repository.findById(id).orElseThrow(()->new NotFoundException("Provider not found"));}
    private void apply(Provider p,Request r){var uri=validator.validate(r.baseUrl());p.name=r.name().trim();p.slug=r.slug();p.baseUrl=uri.toString().replaceAll("/$","");p.environment=r.environment();p.enabled=r.enabled();}
    private Response response(Provider p){return new Response(p.id,p.name,p.slug,p.baseUrl,p.environment,p.enabled,p.createdAt,p.updatedAt);}
    private void log(String action,Provider p){audit.record("ADMIN","admin",action,"SUCCESS",p.id,null,null,null,p.slug,UUID.randomUUID().toString());}
}
