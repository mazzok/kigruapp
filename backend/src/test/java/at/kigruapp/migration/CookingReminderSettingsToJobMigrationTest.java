package at.kigruapp.migration;

import at.kigruapp.entity.CookingReminderSettings;
import at.kigruapp.entity.MailAccount;
import at.kigruapp.entity.MailJob;
import at.kigruapp.entity.MailTemplate;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CookingReminderSettingsToJobMigrationTest {

    @Inject
    CookingReminderSettingsToJobMigration migration;

    @BeforeEach
    void cleanup() {
        MailJob.deleteAll();
        MailTemplate.deleteAll();
        MailAccount.deleteAll();
        CookingReminderSettings.deleteAll();
    }

    private MailAccount persistAccount() {
        MailAccount account = new MailAccount();
        account.name = "Kindergarten";
        account.enabled = true;
        account.persist();
        return account;
    }

    private MailTemplate persistTemplate(String name) {
        MailTemplate template = new MailTemplate();
        template.name = name;
        template.bodyHtml = "<p>Am {{duty.date}} kochst du.</p>";
        template.createdAt = Instant.now();
        template.updatedAt = template.createdAt;
        template.persist();
        return template;
    }

    private void persistSettings(MailAccount account, MailTemplate template) {
        CookingReminderSettings settings = new CookingReminderSettings();
        settings.senderAccountId = account.id.toHexString();
        settings.templateId = template.id.toHexString();
        settings.subject = "Dein Kochdienst";
        settings.sendTime = "07:30";
        settings.updatedAt = Instant.now();
        settings.persist();
    }

    @Test
    void adoptsTheTemplateWhenNoGeneralJobUsesIt() {
        MailAccount account = persistAccount();
        MailTemplate template = persistTemplate("Erinnerung");
        persistSettings(account, template);

        migration.run();

        MailJob job = MailJob.find("kind", MailJob.KIND_COOKING).firstResult();
        assertNotNull(job);
        assertEquals("Kochdienst-Erinnerung", job.name);
        assertEquals("Dein Kochdienst", job.subject);
        assertEquals("07:30", job.sendTime);
        assertTrue(job.active);
        assertEquals(template.id, job.templateId);
        assertEquals(MailTemplate.KIND_COOKING,
                MailTemplate.<MailTemplate>findById(template.id).kind);
        assertEquals(1, MailTemplate.count());
    }

    @Test
    void copiesTheTemplateWhenAGeneralJobUsesIt() {
        MailAccount account = persistAccount();
        MailTemplate template = persistTemplate("Geteilt");
        persistSettings(account, template);

        MailJob general = new MailJob();
        general.name = "Newsletter";
        general.templateId = template.id;
        general.subject = "News";
        general.senderAccountId = account.id.toHexString();
        general.cron = "0 0 8 * * ?";
        general.createdAt = Instant.now();
        general.updatedAt = general.createdAt;
        general.persist();

        migration.run();

        MailJob job = MailJob.find("kind", MailJob.KIND_COOKING).firstResult();
        assertNotNull(job);
        assertEquals(2, MailTemplate.count());
        assertEquals(MailTemplate.KIND_GENERAL,
                MailTemplate.<MailTemplate>findById(template.id).effectiveKind());
        MailTemplate copy = MailTemplate.findById(job.templateId);
        assertEquals("Geteilt (Kochdienst)", copy.name);
        assertEquals(MailTemplate.KIND_COOKING, copy.kind);
    }

    @Test
    void isIdempotent() {
        MailAccount account = persistAccount();
        persistSettings(account, persistTemplate("Erinnerung"));

        migration.run();
        migration.run();

        assertEquals(1, MailJob.count("kind", MailJob.KIND_COOKING));
    }

    @Test
    void doesNothingWithoutSettings() {
        migration.run();

        assertEquals(0, MailJob.count());
    }
}
