package at.kigruapp.security;

import at.kigruapp.entity.Person;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class SecurityFilterTest {

    @Mock
    CurrentUserService currentUserService;

    @Mock
    ContainerRequestContext ctx;

    @Mock
    UriInfo uriInfo;

    SecurityFilter filter;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        filter = new SecurityFilter();
        filter.currentUserService = currentUserService;
        // mongoClient and databaseName not needed for path-only tests
        when(ctx.getUriInfo()).thenReturn(uriInfo);
    }

    private void givenPath(String path, String method) {
        when(uriInfo.getPath()).thenReturn(path);
        when(ctx.getMethod()).thenReturn(method);
    }

    private void assertForbidden() {
        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(captor.capture());
        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), captor.getValue().getStatus());
    }

    private void assertPassThrough() {
        verify(ctx, never()).abortWith(any());
    }

    // 1. Setup path passes through without auth check
    @Test
    void setupPath_passesThrough_withoutAuthCheck() {
        givenPath("/api/v1/setup/init", "POST");

        filter.filter(ctx);

        verifyNoInteractions(currentUserService);
        assertPassThrough();
    }

    // 2. No person → 403
    @Test
    void noCurrentPerson_returns403() {
        givenPath("/api/v1/cooking-duties", "GET");
        when(currentUserService.getCurrentPerson()).thenReturn(null);

        filter.filter(ctx);

        assertForbidden();
    }

    // 3. GET /cooking-duties — authenticated non-admin → allowed
    @Test
    void getCookingDuties_nonAdmin_allowed() {
        givenPath("/api/v1/cooking-duties", "GET");
        Person person = new Person();
        when(currentUserService.getCurrentPerson()).thenReturn(person);
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertPassThrough();
    }

    // 4. GET /persons (not /me) — non-admin → 403
    @Test
    void getPersons_nonAdmin_returns403() {
        givenPath("/api/v1/persons", "GET");
        Person person = new Person();
        when(currentUserService.getCurrentPerson()).thenReturn(person);
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertForbidden();
    }

    // 5. GET /persons — admin → allowed
    @Test
    void getPersons_admin_allowed() {
        givenPath("/api/v1/persons", "GET");
        Person person = new Person();
        when(currentUserService.getCurrentPerson()).thenReturn(person);
        when(currentUserService.isAdmin()).thenReturn(true);

        filter.filter(ctx);

        assertPassThrough();
    }

    // 6. GET /field-definitions — non-admin → allowed
    @Test
    void getFieldDefinitions_nonAdmin_allowed() {
        givenPath("/api/v1/field-definitions", "GET");
        Person person = new Person();
        when(currentUserService.getCurrentPerson()).thenReturn(person);
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertPassThrough();
    }

    // 7. POST /field-definitions — non-admin → 403
    @Test
    void postFieldDefinitions_nonAdmin_returns403() {
        givenPath("/api/v1/field-definitions", "POST");
        Person person = new Person();
        when(currentUserService.getCurrentPerson()).thenReturn(person);
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertForbidden();
    }

    // 8. GET /persons/me — non-admin → allowed
    @Test
    void getPersonsMe_nonAdmin_allowed() {
        givenPath("/api/v1/persons/me", "GET");
        Person person = new Person();
        when(currentUserService.getCurrentPerson()).thenReturn(person);
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertPassThrough();
    }

    // 9. GET /organisation/groups — non-admin → allowed
    @Test
    void getOrganisationGroups_nonAdmin_allowed() {
        givenPath("/api/v1/organisation/groups", "GET");
        Person person = new Person();
        when(currentUserService.getCurrentPerson()).thenReturn(person);
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertPassThrough();
    }

    // 10. GET /field-instances — non-admin → allowed
    @Test
    void getFieldInstances_nonAdmin_allowed() {
        givenPath("/api/v1/field-instances", "GET");
        Person person = new Person();
        when(currentUserService.getCurrentPerson()).thenReturn(person);
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertPassThrough();
    }

    // 11. POST /field-instances — non-admin → allowed (resource validates)
    @Test
    void postFieldInstances_nonAdmin_allowed() {
        givenPath("/api/v1/field-instances", "POST");
        Person person = new Person();
        when(currentUserService.getCurrentPerson()).thenReturn(person);
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertPassThrough();
    }

    // 12. PUT /organisation/duty-settings — non-admin → 403
    @Test
    void putOrganisationDutySettings_nonAdmin_returns403() {
        givenPath("/api/v1/organisation/duty-settings", "PUT");
        Person person = new Person();
        when(currentUserService.getCurrentPerson()).thenReturn(person);
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertForbidden();
    }

    // 13. GET /families — non-admin → 403
    @Test
    void getFamilies_nonAdmin_returns403() {
        givenPath("/api/v1/families", "GET");
        Person person = new Person();
        when(currentUserService.getCurrentPerson()).thenReturn(person);
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertForbidden();
    }

    // 14. Unknown path — non-admin → 403 (safe default)
    @Test
    void unknownPath_nonAdmin_returns403() {
        givenPath("/api/v1/unknown-resource", "GET");
        Person person = new Person();
        when(currentUserService.getCurrentPerson()).thenReturn(person);
        when(currentUserService.isAdmin()).thenReturn(false);

        filter.filter(ctx);

        assertForbidden();
    }
}
