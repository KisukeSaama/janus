package io.janus.applications;

import io.janus.audit.AuditService;
import io.janus.shared.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

@RestController @RequestMapping("/api/admin/applications")
public class ApplicationAdminController {
    private final ApplicationRepository repository; private final PasswordEncoder encoder; private final AuditService audit; private final SecureRandom random=new SecureRandom();
    public ApplicationAdminController(ApplicationRepository repository, PasswordEncoder encoder, AuditService audit){this.repository=repository;this.encoder=encoder;this.audit=audit;}
    public record Request(@NotBlank @Size(max=120) String name, @Size(max=500) String description, @NotNull Environment environment, boolean enabled){}
    public record Response(UUID id,String name,String description,Environment environment,boolean enabled,Instant createdAt,Instant updatedAt){}
    public record Created(Response application,String apiKey){}
    @GetMapping @Transactional(readOnly=true) public List<Response> list(){return repository.findAll().stream().map(this::response).toList();}
    @PostMapping @Transactional public Created create(@Valid @RequestBody Request req){var key=key();var a=new Application();apply(a,req);a.apiKeyHash=encoder.encode(key);repository.save(a);adminAudit("APPLICATION_CREATED",a.id.toString());return new Created(response(a),key);}
    @PutMapping("/{id}") @Transactional public Response update(@PathVariable UUID id,@Valid @RequestBody Request req){var a=get(id);if(a.environment!=req.environment())throw new IllegalArgumentException("Application environment cannot be changed; create a separate application");apply(a,req);adminAudit("APPLICATION_UPDATED",id.toString());return response(a);}
    @PostMapping("/{id}/rotate-key") @Transactional public Created rotate(@PathVariable UUID id){var a=get(id);var key=key();a.apiKeyHash=encoder.encode(key);adminAudit("APPLICATION_KEY_ROTATED",id.toString());return new Created(response(a),key);}
    @DeleteMapping("/{id}") @Transactional public void delete(@PathVariable UUID id){repository.delete(get(id));adminAudit("APPLICATION_DELETED",id.toString());}
    private Application get(UUID id){return repository.findById(id).orElseThrow(()->new NotFoundException("Application not found"));}
    private void apply(Application a,Request r){a.name=r.name().trim();a.description=r.description();a.environment=r.environment();a.enabled=r.enabled();}
    private Response response(Application a){return new Response(a.id,a.name,a.description,a.environment,a.enabled,a.createdAt,a.updatedAt);}
    private String key(){byte[] b=new byte[32];random.nextBytes(b);return "jns_"+Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    private void adminAudit(String action,String detail){audit.record("ADMIN","admin",action,"SUCCESS",null,null,null,null,detail,UUID.randomUUID().toString());}
}
