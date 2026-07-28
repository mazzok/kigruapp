package at.kigruapp.exception;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

/**
 * Serializes JAX-RS {@link WebApplicationException}s (e.g. {@code BadRequestException},
 * {@code NotFoundException}) into a small JSON body {@code {"message": "..."}} while
 * preserving the original status code.
 *
 * <p>RESTEasy Reactive otherwise returns these with an empty body, so the frontend has
 * no reason to show the user. This mapper lets the client surface the real validation
 * message (e.g. "invalid cron expression: ..."). Exceptions that already carry an entity
 * are passed through untouched.
 */
@Provider
public class WebApplicationExceptionMapper implements ExceptionMapper<WebApplicationException> {

    @Override
    public Response toResponse(WebApplicationException exception) {
        Response original = exception.getResponse();
        int status = original != null ? original.getStatus() : Response.Status.INTERNAL_SERVER_ERROR.getStatusCode();

        // Respect any body the throwing code already set.
        if (original != null && original.hasEntity()) {
            return original;
        }

        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = Response.Status.fromStatusCode(status) != null
                    ? Response.Status.fromStatusCode(status).getReasonPhrase()
                    : "Request failed";
        }

        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("message", message))
                .build();
    }
}
