package at.kigruapp.security;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.List;

@ApplicationScoped
public class KeycloakUserService {

    @ConfigProperty(name = "kigruapp.keycloak.server-url", defaultValue = "http://keycloak:8443")
    String serverUrl;

    @ConfigProperty(name = "kigruapp.keycloak.realm", defaultValue = "kigruapp")
    String realm;

    @ConfigProperty(name = "kigruapp.keycloak.admin-username", defaultValue = "admin")
    String adminUsername;

    @ConfigProperty(name = "kigruapp.keycloak.admin-password", defaultValue = "admin")
    String adminPassword;

    public String createUser(String email, String firstName, String lastName) {
        try (Keycloak keycloak = KeycloakBuilder.builder()
                .serverUrl(serverUrl)
                .realm("master")
                .clientId("admin-cli")
                .username(adminUsername)
                .password(adminPassword)
                .build()) {

            UserRepresentation user = new UserRepresentation();
            user.setEnabled(true);
            user.setUsername(email);
            user.setEmail(email);
            user.setFirstName(firstName);
            user.setLastName(lastName);
            user.setEmailVerified(false);
            user.setRequiredActions(List.of("UPDATE_PASSWORD"));

            var response = keycloak.realm(realm).users().create(user);
            String userId = response.getLocation().getPath()
                .replaceAll(".*/([^/]+)$", "$1");

            CredentialRepresentation cred = new CredentialRepresentation();
            cred.setTemporary(true);
            cred.setType(CredentialRepresentation.PASSWORD);
            cred.setValue("changeme");
            keycloak.realm(realm).users().get(userId).resetPassword(cred);

            return userId;
        }
    }
}
