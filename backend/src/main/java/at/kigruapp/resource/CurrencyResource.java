package at.kigruapp.resource;

import at.kigruapp.entity.Currency;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/v1/currencies")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CurrencyResource {

    public record CreateCurrencyRequest(String code, String symbol) {}

    @GET
    public List<Currency> list() {
        return Currency.listAll();
    }

    @POST
    public Response create(CreateCurrencyRequest request) {
        if (request.code() == null || request.code().isBlank()) {
            throw new BadRequestException("code is required");
        }
        if (request.symbol() == null || request.symbol().isBlank()) {
            throw new BadRequestException("symbol is required");
        }

        Currency currency = new Currency();
        currency.code = request.code();
        currency.symbol = request.symbol();
        currency.persist();
        return Response.status(201).entity(currency).build();
    }
}
