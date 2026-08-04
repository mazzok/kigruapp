package at.kigruapp.resource;

import at.kigruapp.entity.MailAccount;
import at.kigruapp.entity.MailJob;
import at.kigruapp.entity.MailTemplate;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class CookingReminderSettingsResourceTest {

    @BeforeEach
    void cleanup() {
        MailJob.deleteAll();
        MailAccount.deleteAll();
        MailTemplate.deleteAll();
    }

    private MailAccount persistAccount(boolean enabled) {
        MailAccount account = new MailAccount();
        account.name = "Kindergarten";
        account.enabled = enabled;
        account.persist();
        return account;
    }

    private MailTemplate persistCookingTemplate() {
        MailTemplate template = new MailTemplate();
        template.name = "Erinnerung";
        template.bodyHtml = "<p>Hallo</p>";
        template.kind = MailTemplate.KIND_COOKING;
        template.createdAt = Instant.now();
        template.updatedAt = template.createdAt;
        template.persist();
        return template;
    }

    private MailJob persistCookingJob(MailAccount account, MailTemplate template, boolean active) {
        MailJob job = new MailJob();
        job.kind = MailJob.KIND_COOKING;
        job.name = "Erinnerung";
        job.subject = "Dein Kochdienst";
        job.sendTime = "07:00";
        job.senderAccountId = account.id.toHexString();
        job.templateId = template.id;
        job.active = active;
        job.createdAt = Instant.now();
        job.updatedAt = job.createdAt;
        job.persist();
        return job;
    }

    @Test
    void inactiveWithoutAnyJob() {
        given()
                .when().get("/api/v1/cooking-reminder-settings")
                .then().statusCode(200)
                .body("active", is(false));
    }

    @Test
    void activeWithOneSendableJob() {
        MailAccount account = persistAccount(true);
        MailTemplate template = persistCookingTemplate();
        persistCookingJob(account, template, true);

        given()
                .when().get("/api/v1/cooking-reminder-settings")
                .then().statusCode(200)
                .body("active", is(true));
    }

    @Test
    void inactiveWhenTheOnlyJobIsInactiveOrItsAccountIsDisabled() {
        MailAccount disabled = persistAccount(false);
        persistCookingJob(disabled, persistCookingTemplate(), true);

        given()
                .when().get("/api/v1/cooking-reminder-settings")
                .then().statusCode(200)
                .body("active", is(false));
    }
}
