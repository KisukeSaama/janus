package io.janus.grants;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

/** HTTP surface for access grants. Every decision belongs to {@link GrantService}. */
@RestController
@RequestMapping("/api/admin/grants")
public class GrantAdminController {
    private final GrantService grants;

    public GrantAdminController(GrantService grants) {
        this.grants = grants;
    }

    @GetMapping
    public List<GrantResponse> list() {
        return grants.list();
    }

    @PostMapping
    public GrantResponse create(@Valid @RequestBody GrantRequest request) {
        return grants.create(request);
    }

    @PutMapping("/{id}")
    public GrantResponse update(@PathVariable UUID id, @Valid @RequestBody GrantRequest request) {
        return grants.update(id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        grants.delete(id);
    }
}
