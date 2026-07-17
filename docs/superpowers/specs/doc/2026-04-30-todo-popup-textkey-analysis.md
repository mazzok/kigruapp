# Analysis: TODO/Dialog Popup Text Key Flow (Ticket: Popups Not Displayed Reliably on RSP)

**Date:** 2026-04-30
**Status:** Investigation complete, no fix planned yet

## Background

Customer reported that dialog/TODO popups on the RSP were not displayed reliably after
prolonged runtime. A newly observed symptom was: a dialog text was shown on the RSP that
no longer exists under the corresponding text key in the development environment.

The initial hypothesis was that the MQTT broker was caching and serving stale dialog text
(retained messages). A test was performed to verify whether the RSP would fail to receive
updated text keys after a CSP configuration change.

## Test Performed

1. CSP running, RSP restarted
2. CSP triggers a TODO (message) with a given text key
3. Both CSP and RSP display the TODO correctly
4. CSP is stopped, the text key value is changed, CSP restarted
5. CSP sends the same TODO again

**Result:** RSP displayed the updated text. The hypothesis that RSP would show the old
text was disproven. The TOC sync delivers the current CSP values to the RSP upon CSP
restart, including updated text key values.

## Code Analysis: How Text Keys and Values Reach the RSP

There are two code paths that can trigger a TODO/dialog popup:

### Path 1: MessageStep (message specification with textKey)

- The CSP publishes message specifications via **`MessagesToc`** (MQTT topic, retained).
- Language-specific text values are published via **`TextsToc`** (MQTT topic, retained).
- On RSP side, `TocParser` parses both TOCs and populates the local model.
- At display time, `BeanTextManager.getText(textKey)` resolves the key via
  `TextManager` → `TextChannel` → language-specific text value.
- **Source:** Entirely MQTT-based from CSP. No local bioguilib fallback for CSP texts.

### Path 2: ShowDialogStep (recipe step with plain text)

- The text is stored as a plain string directly in the recipe step parameter (`"text"`).
- The recipe itself is delivered to the RSP via **`MqttRecipeIngester`** (MQTT, retained).
- At display time, `SeqMsgContentProcessor` passes the plain text directly to
  `SeqMsgDialog` — no text key resolution involved.
- **Source:** Entirely MQTT-based from CSP (via recipe). No local bioguilib fallback.

### Local bioguilib text (NOT relevant for CSP messages)

- `bioguilib/text_cfg/asphere.xml` contains static UI labels and system texts.
- This is loaded locally on startup and is NOT used for CSP-specific message or dialog text.

## Summary

| Text path | Source | MQTT retained? |
|-----------|--------|----------------|
| MessageStep (textKey) | MessagesToc + TextsToc from CSP | Yes |
| ShowDialogStep (plain text) | Recipe via MqttRecipeIngester from CSP | Yes |
| System UI labels | bioguilib `text_cfg/asphere.xml` (local) | No |

## Root Cause Assessment

All CSP-specific dialog and TODO text reaches the RSP exclusively via MQTT retained
messages. The test confirmed the TOC sync works correctly on CSP restart. The original
symptom (stale/nonexistent text shown after prolonged runtime or config changes) is
consistent with **MQTT broker retained messages that were not invalidated** after a
configuration change on the CSP side.

Restarting CSP/RSP can temporarily resolve the issue because the TOC is re-published
with current values on CSP startup, overwriting the broker's retained message cache.

## MQTT Retained Message Structure (Deep Analysis)

### Topic structure

TOC data is **not** published as individual topics per text key or message spec.
Instead, all content is bundled into one retained JSON message per TOC part:

| Topic | Content | Retained |
|-------|---------|---------|
| `toc/{clientName}/items` | Full item tree as JSON | Yes |
| `toc/{clientName}/messages` | All message specs + groups as JSON | Yes |
| `toc/{clientName}/texts` | All text keys with all language translations as JSON | Yes |
| `toc/{clientName}/units` | All unit definitions as JSON | Yes |

Published by `DataSource.start()` → `publishTableOfContents()` on every CSP startup.

### Behavior on CSP restart

When the CSP restarts, it calls `publishTableOfContents()` which republishes all four
TOC topics with the **complete current configuration**. This overwrites the broker's
retained message for each topic. As a result:

- If a text key was removed from the CSP config **and** the CSP was restarted,
  the new TextsToc JSON no longer contains that key.
- The broker's retained message for `toc/.../texts` is overwritten with the new JSON.
- RSP receives the new TOC, parses it, and the removed key disappears from its model.

### No cleanup mechanism for removed entries

There is **no code path** that sends an empty or null retained message to explicitly
clear a TOC topic. Removal of items only takes effect when the CSP republishes the
complete TOC on the next startup. Specific findings:

- No `publish(topic, null)` or `publish(topic, "")` pattern exists anywhere
- No `clearRetained()` or equivalent mechanism
- TOC classes (`MessagesToc`, `TextsToc`, `ItemsToc`) only implement `createToc()` and
  `parseToc()` — no delete or cleanup methods
- `parseToc()` on the RSP side replaces the in-memory model with the new data, but
  does not explicitly remove entries not present in the new TOC

### Where stale data can originate

Given the bundle-based publishing, stale data on the broker can only arise in one scenario:

> **The CSP configuration was changed (text key removed/modified) but the CSP was NOT
> restarted afterwards.** The broker continues to serve the old retained TOC JSON
> because no new publish has overwritten it.

This is consistent with the customer symptom: "a dialog text was displayed that no longer
exists in the development environment." The production CSP's retained TOC still contained
the old text because the CSP had not been restarted since the config was changed
(or the updated config was never deployed to the production CSP).

### Why restarts help

A CSP restart triggers `publishTableOfContents()`, which overwrites all four retained
TOC topics with the current configuration. This explains why restarting CSP/RSP temporarily
resolves the issue: it forces the broker to receive and retain the current config state.

### Conclusion: Not a broker cache invalidation problem

The root cause is **not** a missing broker cache invalidation mechanism. The broker behaves
correctly — it retains whatever the CSP last published. The actual problem is:

1. **Config/deployment mismatch:** The production CSP config was not updated when the
   development config changed, or the CSP was not restarted after the update.
2. **No runtime config reload:** The CSP has no mechanism to republish the TOC without
   a full restart. Any configuration change requires a CSP restart to take effect on
   the broker's retained messages.

A future improvement could be to trigger `publishTableOfContents()` immediately when
configuration is changed at runtime, without requiring a full CSP restart.
