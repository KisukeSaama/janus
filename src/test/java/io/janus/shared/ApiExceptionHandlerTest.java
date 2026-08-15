package io.janus.shared;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import io.janus.gateway.GatewayRequestSizeFilter.PayloadTooLargeException;

/**
 * How failures leave the building.
 *
 * <p>Two things are being asserted throughout: a client mistake is answered precisely enough to be
 * fixed, and anything unexpected is answered generically. An internal message that reaches the
 * caller becomes part of the API surface, and the ones worth reading tend to quote connection
 * strings.
 */
class ApiExceptionHandlerTest {

    /** Stands in for every controller, so each branch can be reached the way a real one reaches it. */
    @RestController
    @RequestMapping("/things")
    static class ThingController {

        record Thing(@NotBlank String name, @Min(1) int size, boolean active) {}

        @GetMapping("/{id}")
        String byId(@PathVariable UUID id) {
            return "found";
        }

        @GetMapping("/search")
        String search(@RequestParam String query) {
            return "found " + query;
        }

        @PostMapping
        String create(@Valid @RequestBody Thing thing) {
            return thing.name();
        }

        @GetMapping("/missing")
        String missing() {
            throw new NotFoundException("Thing not found");
        }

        @GetMapping("/refused")
        String refused() {
            throw new IllegalArgumentException("That name is already taken");
        }

        @GetMapping("/conflicting")
        String conflicting() {
            throw new DataIntegrityViolationException("duplicate key value violates uq_thing_name");
        }

        @GetMapping("/too-large")
        String tooLarge() {
            throw new PayloadTooLargeException("body too large");
        }

        @GetMapping("/broken")
        String broken() {
            throw new RuntimeException("jdbc:postgresql://janus:hunter2@db:5432/janus refused the connection");
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ThingController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    // --- mistakes the caller can fix -----------------------------------------

    @Test
    void reportsSomethingThatDoesNotExistAsNotFound() throws Exception {
        mvc.perform(get("/things/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Thing not found"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void reportsARefusedOperationWithTheReasonItWasRefused() throws Exception {
        mvc.perform(get("/things/refused"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("That name is already taken"));
    }

    /** Field by field, because "validation failed" alone cannot be acted on. */
    @Test
    void namesTheFieldsThatFailedValidation() throws Exception {
        mvc.perform(post("/things")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"size\":0,\"active\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Request validation failed"))
                .andExpect(jsonPath("$.errors.name").isNotEmpty())
                .andExpect(jsonPath("$.errors.size").isNotEmpty());
    }

    /**
     * Jackson refuses a missing primitive rather than defaulting it, so a body that merely omits a
     * field arrives as a parse failure. Answering "not valid JSON" for well-formed JSON sends a
     * client hunting for a syntax error that does not exist.
     */
    @Test
    void reportsAnOmittedFieldAsAMissingFieldRatherThanAsBrokenJson() throws Exception {
        mvc.perform(post("/things").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"thing\",\"size\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Request validation failed"))
                .andExpect(jsonPath("$.errors.active").value("is required"));
    }

    @Test
    void reportsAFieldOfTheWrongTypeAsUnusableRatherThanAsBrokenJson() throws Exception {
        mvc.perform(post("/things")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"thing\",\"size\":\"large\",\"active\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.size").value("has an unusable value"));
    }

    @Test
    void reportsABodyThatIsNotJsonAsSuch() throws Exception {
        mvc.perform(post("/things").contentType(MediaType.APPLICATION_JSON).content("not json at all"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The request body is not valid JSON for this endpoint"));
    }

    @Test
    void reportsAnUnusablePathParameterByName() throws Exception {
        mvc.perform(get("/things/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Parameter 'id' has an unusable value"));
    }

    @Test
    void reportsAMissingQueryParameterByName() throws Exception {
        mvc.perform(get("/things/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Parameter 'query' is required"));
    }

    @Test
    void reportsAMethodTheEndpointDoesNotSupport() throws Exception {
        mvc.perform(delete("/things")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void reportsAnUnsupportedContentType() throws Exception {
        mvc.perform(post("/things").contentType(MediaType.TEXT_PLAIN).content("name=thing"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.detail").value("This endpoint requires application/json"));
    }

    @Test
    void reportsAnOversizedBodyAsSuch() throws Exception {
        mvc.perform(get("/things/too-large")).andExpect(status().isContentTooLarge());
    }

    /** The database's message names constraints and columns; the caller is told what it can act on. */
    @Test
    void reportsAConflictWithoutQuotingTheConstraintThatCaught() throws Exception {
        mvc.perform(get("/things/conflicting"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("The record is still referenced or conflicts with an existing record"))
                .andExpect(
                        content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("uq_thing"))));
    }

    // --- everything else ------------------------------------------------------

    /** The one that matters most: an unexpected message must never become part of the API. */
    @Test
    void answersAnUnexpectedFailureGenericallyAndKeepsItsMessageInternal() throws Exception {
        mvc.perform(get("/things/broken"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("The request could not be completed"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("hunter2"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("jdbc"))));
    }
}
