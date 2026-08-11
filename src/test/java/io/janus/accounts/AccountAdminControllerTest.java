package io.janus.accounts;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import io.janus.audit.AuditService;
import io.janus.shared.ApiExceptionHandler;

class AccountAdminControllerTest {
    private static final String STRONG = "7Qb!vTz2LmXe4RpA9dWf";

    private final AccountRepository repository = Mockito.mock(AccountRepository.class);
    private final AccessScope scope = Mockito.mock(AccessScope.class);
    private final RegistryTransfer registry = Mockito.mock(RegistryTransfer.class);
    private final AuditService audit = Mockito.mock(AuditService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var service = new AccountService(repository, new BCryptPasswordEncoder(4), scope, registry, audit);
        when(registry.holdings(any())).thenReturn(new RegistryTransfer.Holdings(0, 0));
        mvc = MockMvcBuilders.standaloneSetup(new AccountAdminController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
        when(repository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));
        callerIs(AccountRole.SUPER_ADMIN);
    }

    /** Puts a signed-in person of the given role behind the request. */
    private ConsoleUser callerIs(AccountRole role) {
        var caller = new ConsoleUser(account("caller", role));
        when(scope.current()).thenReturn(caller);
        when(scope.accountId()).thenReturn(caller.id());
        return caller;
    }

    private static Account account(String username, AccountRole role) {
        return new Account(username, "Someone", username + "@example.com", "hash", role, true);
    }

    private static String body(String username, String role, String password, boolean enabled) {
        return """
               {"username":"%s","displayName":"Someone","email":"%s@example.com","role":"%s","password":"%s","enabled":%s}"""
                .formatted(username, username, role, password, enabled);
    }

    private ResultActionsWrapper createAccount(String username, String role, String password) throws Exception {
        return new ResultActionsWrapper(mvc.perform(post("/api/admin/accounts")
                .contentType("application/json")
                .content(body(username, role, password, true))));
    }

    /** Keeps the call sites reading as sentences rather than as request builders. */
    private record ResultActionsWrapper(org.springframework.test.web.servlet.ResultActions actions) {}

    @Test
    void neverReturnsAPasswordHash() throws Exception {
        mvc.perform(post("/api/admin/accounts")
                        .contentType("application/json")
                        .content(body("bobby6", "USER", STRONG, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("bobby6"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("passwordHash"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(STRONG))));
    }

    @Test
    void refusesAUsernameShorterThanSixCharacters() throws Exception {
        createAccount("bobby", "USER", STRONG).actions().andExpect(status().isBadRequest());
        verify(repository, never()).save(any());
    }

    @Test
    void refusesAUsernameThatIsAlreadyTaken() throws Exception {
        when(repository.existsByUsername("bobby6")).thenReturn(true);
        createAccount("bobby6", "USER", STRONG).actions().andExpect(status().isBadRequest());
        verify(repository, never()).save(any());
    }

    @Test
    void aWeakPasswordIsRejected() throws Exception {
        createAccount("bobby6", "USER", "short").actions().andExpect(status().isBadRequest());
        verify(repository, never()).save(any());
    }

    /** A login is compared, never displayed as typed, so two accounts cannot differ by case alone. */
    @Test
    void aUsernameIsComparedInLowerCase() throws Exception {
        when(repository.existsByUsername("bobby6")).thenReturn(true);
        createAccount("BOBBY6", "USER", STRONG).actions().andExpect(status().isBadRequest());
    }

    // --- who may act on whom ------------------------------------------------

    /** An administrator appoints peers; that is what "can name other admins" means. */
    @Test
    void anAdministratorMayAppointAnotherAdministrator() throws Exception {
        callerIs(AccountRole.ADMIN);
        createAccount("bobby6", "ADMIN", STRONG)
                .actions()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void anAdministratorMayNotAppointASuperAdministrator() throws Exception {
        callerIs(AccountRole.ADMIN);
        createAccount("bobby6", "SUPER_ADMIN", STRONG).actions().andExpect(status().isBadRequest());
        verify(repository, never()).save(any());
    }

    /** Peers do not hold power over each other, or whichever one acts first wins. */
    @Test
    void anAdministratorMayNotEditAnotherAdministrator() throws Exception {
        callerIs(AccountRole.ADMIN);
        var peer = account("other", AccountRole.ADMIN);
        when(repository.findById(peer.getId())).thenReturn(Optional.of(peer));

        mvc.perform(put("/api/admin/accounts/" + peer.getId())
                        .contentType("application/json")
                        .content(body("other", "USER", "", true)))
                .andExpect(status().isBadRequest());
        org.assertj.core.api.Assertions.assertThat(peer.getRole()).isEqualTo(AccountRole.ADMIN);
    }

    @Test
    void anAdministratorMayNotDeleteAnotherAdministrator() throws Exception {
        callerIs(AccountRole.ADMIN);
        var peer = account("other", AccountRole.ADMIN);
        when(repository.findById(peer.getId())).thenReturn(Optional.of(peer));

        mvc.perform(delete("/api/admin/accounts/" + peer.getId())).andExpect(status().isBadRequest());
        verify(repository, never()).delete(any());
    }

    @Test
    void anAdministratorManagesOrdinaryAccounts() throws Exception {
        callerIs(AccountRole.ADMIN);
        var user = account("bo", AccountRole.USER);
        when(repository.findById(user.getId())).thenReturn(Optional.of(user));

        mvc.perform(put("/api/admin/accounts/" + user.getId())
                        .contentType("application/json")
                        .content(body("bo", "USER", "", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void aSuperAdministratorManagesAdministrators() throws Exception {
        var admin = account("other", AccountRole.ADMIN);
        when(repository.findById(admin.getId())).thenReturn(Optional.of(admin));

        mvc.perform(put("/api/admin/accounts/" + admin.getId())
                        .contentType("application/json")
                        .content(body("other", "USER", "", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("USER"));
    }

    // --- keeping the deployment administrable -------------------------------

    @Test
    void refusesToDeleteTheLastSuperAdministrator() throws Exception {
        var last = account("root", AccountRole.SUPER_ADMIN);
        when(repository.findById(last.getId())).thenReturn(Optional.of(last));
        when(repository.countByRoleAndEnabledTrue(AccountRole.SUPER_ADMIN)).thenReturn(1L);

        mvc.perform(delete("/api/admin/accounts/" + last.getId())).andExpect(status().isBadRequest());
        verify(repository, never()).delete(any());
    }

    @Test
    void refusesToDemoteTheLastSuperAdministrator() throws Exception {
        var last = account("root", AccountRole.SUPER_ADMIN);
        when(repository.findById(last.getId())).thenReturn(Optional.of(last));
        when(repository.countByRoleAndEnabledTrue(AccountRole.SUPER_ADMIN)).thenReturn(1L);

        mvc.perform(put("/api/admin/accounts/" + last.getId())
                        .contentType("application/json")
                        .content(body("root", "ADMIN", "", true)))
                .andExpect(status().isBadRequest());
        org.assertj.core.api.Assertions.assertThat(last.getRole()).isEqualTo(AccountRole.SUPER_ADMIN);
    }

    /** Disabling the last one locks the deployment out just as thoroughly as deleting them. */
    @Test
    void refusesToDisableTheLastSuperAdministrator() throws Exception {
        var last = account("root", AccountRole.SUPER_ADMIN);
        when(repository.findById(last.getId())).thenReturn(Optional.of(last));
        when(repository.countByRoleAndEnabledTrue(AccountRole.SUPER_ADMIN)).thenReturn(1L);

        mvc.perform(put("/api/admin/accounts/" + last.getId())
                        .contentType("application/json")
                        .content(body("root", "SUPER_ADMIN", "", false)))
                .andExpect(status().isBadRequest());
        org.assertj.core.api.Assertions.assertThat(last.isEnabled()).isTrue();
    }

    @Test
    void demotingASuperAdministratorIsAllowedWhileAnotherOneRemains() throws Exception {
        var one = account("root", AccountRole.SUPER_ADMIN);
        when(repository.findById(one.getId())).thenReturn(Optional.of(one));
        when(repository.countByRoleAndEnabledTrue(AccountRole.SUPER_ADMIN)).thenReturn(2L);

        mvc.perform(put("/api/admin/accounts/" + one.getId())
                        .contentType("application/json")
                        .content(body("root", "ADMIN", "", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void anAccountCannotDeleteItself() throws Exception {
        var caller = callerIs(AccountRole.SUPER_ADMIN);
        var self = account("caller", AccountRole.SUPER_ADMIN);
        when(repository.findById(caller.id())).thenReturn(Optional.of(self));
        when(repository.findById(self.getId())).thenReturn(Optional.of(self));
        when(scope.accountId()).thenReturn(self.getId());
        when(scope.current()).thenReturn(new ConsoleUser(self));
        when(repository.countByRoleAndEnabledTrue(AccountRole.SUPER_ADMIN)).thenReturn(2L);

        mvc.perform(delete("/api/admin/accounts/" + self.getId())).andExpect(status().isBadRequest());
        verify(repository, never()).delete(any());
    }

    // --- ordinary edits -----------------------------------------------------

    /** A blank password on an update means "leave it alone", not "set it to nothing". */
    @Test
    void anUpdateWithoutAPasswordKeepsTheCurrentOne() throws Exception {
        var account = account("bo", AccountRole.USER);
        String before = account.getPasswordHash();
        when(repository.findById(account.getId())).thenReturn(Optional.of(account));

        mvc.perform(put("/api/admin/accounts/" + account.getId())
                        .contentType("application/json")
                        .content(body("bo", "USER", "", true)))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(account.getPasswordHash()).isEqualTo(before);
    }

    @Test
    void updatingAnUnknownAccountIsNotFound() throws Exception {
        var id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        mvc.perform(put("/api/admin/accounts/" + id)
                        .contentType("application/json")
                        .content(body("bo", "USER", "", true)))
                .andExpect(status().isNotFound());
    }
}
