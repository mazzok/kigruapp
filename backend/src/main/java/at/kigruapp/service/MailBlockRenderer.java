package at.kigruapp.service;

import com.fasterxml.jackson.databind.JsonNode;

/** One implementation per mail-template block type; see {@link MailTemplateRenderer}. */
public interface MailBlockRenderer {

    boolean supports(String blockType);

    String render(JsonNode config);
}
