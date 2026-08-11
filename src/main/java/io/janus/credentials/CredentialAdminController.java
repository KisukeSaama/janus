package io.janus.credentials;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

/** HTTP surface for stored secrets. Every decision belongs to {@link CredentialService}. */
@RestController
@RequestMapping("/api/admin/credentials")
public class CredentialAdminController {
    private final CredentialService credentials;

    public CredentialAdminController(CredentialService credentials) {
        this.credentials = credentials;
    }

    @GetMapping
    public List<CredentialResponse> list() {
        return credentials.list();
    }

    @PostMapping
    public CredentialResponse create(@Valid @RequestBody CredentialRequest request) {
        return credentials.create(request);
    }

    @PutMapping("/{id}")
    public CredentialResponse update(@PathVariable UUID id, @Valid @RequestBody CredentialRequest request) {
        return credentials.update(id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        credentials.delete(id);
    }
}
