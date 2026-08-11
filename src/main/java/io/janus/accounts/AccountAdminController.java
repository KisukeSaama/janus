package io.janus.accounts;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

/** HTTP surface for console accounts. Every decision belongs to {@link AccountService}. */
@RestController
@RequestMapping("/api/admin/accounts")
public class AccountAdminController {
    private final AccountService accounts;

    public AccountAdminController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    public List<AccountResponse> list() {
        return accounts.list();
    }

    @PostMapping
    public AccountResponse create(@Valid @RequestBody AccountRequest request) {
        return accounts.create(request);
    }

    @PutMapping("/{id}")
    public AccountResponse update(@PathVariable UUID id, @Valid @RequestBody AccountRequest request) {
        return accounts.update(id, request);
    }

    /**
     * Hands everything {@code id} holds to {@code to}. The one operation that crosses two
     * registries, and therefore the one a super administrator alone may perform.
     */
    @PostMapping("/{id}/transfer")
    public RegistryTransfer.Holdings transfer(@PathVariable UUID id, @RequestParam UUID to) {
        return accounts.transferRecords(id, to);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        accounts.delete(id);
    }
}
