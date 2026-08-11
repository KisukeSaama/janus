package io.janus.applications;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

/** HTTP surface for machine identities. Every decision belongs to {@link ApplicationService}. */
@RestController
@RequestMapping("/api/admin/applications")
public class ApplicationAdminController {
    private final ApplicationService applications;

    public ApplicationAdminController(ApplicationService applications) {
        this.applications = applications;
    }

    @GetMapping
    public List<ApplicationResponse> list() {
        return applications.list();
    }

    @PostMapping
    public IssuedApplication create(@Valid @RequestBody ApplicationRequest request) {
        return applications.create(request);
    }

    @PutMapping("/{id}")
    public ApplicationResponse update(@PathVariable UUID id, @Valid @RequestBody ApplicationRequest request) {
        return applications.update(id, request);
    }

    @PostMapping("/{id}/rotate-key")
    public IssuedApplication rotateKey(@PathVariable UUID id) {
        return applications.rotateKey(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        applications.delete(id);
    }
}
