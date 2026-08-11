package io.janus.providers;

import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

/** HTTP surface for destinations. Every decision belongs to {@link ProviderService}. */
@RestController
@RequestMapping("/api/admin/providers")
public class ProviderAdminController {
    private final ProviderService providers;

    public ProviderAdminController(ProviderService providers) {
        this.providers = providers;
    }

    @GetMapping
    public ProviderPage list(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return providers.catalog(q, page, size);
    }

    @PostMapping
    public ProviderResponse create(@Valid @RequestBody ProviderRequest request) {
        return providers.create(request);
    }

    @PutMapping("/{id}")
    public ProviderResponse update(@PathVariable UUID id, @Valid @RequestBody ProviderRequest request) {
        return providers.update(id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        providers.delete(id);
    }

    @DeleteMapping("/{id}/cache")
    public Map<String, Object> purgeCache(@PathVariable UUID id) {
        return Map.of("purged", providers.purgeCache(id));
    }
}
