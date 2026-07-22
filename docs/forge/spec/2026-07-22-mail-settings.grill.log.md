# Grill work log — 2026-07-22-mail-settings

- Task (verbatim): Adversarial design review of `docs/forge/spec/2026-07-22-mail-settings.md` via forge-grillme. Focus: DB-secret + AES-GCM key mgmt/rotation/loss, raw jakarta.mail vs quarkus-mailer, sync-send-in-request-thread timeout, SSRF outbound surface, empty-password-unchanged semantics, test-endpoint 200-on-failure, "infrastructure only" without a first consumer, testability of R1–R9. Write .grill.md (ID'd findings, Resolution: Unresolved, Verdict block) + this log.
- Started: 2026-07-22
- Session note: fresh reviewer, did NOT author the spec.

## Files read
- docs/forge/spec/2026-07-22-mail-settings.md (target, full)
- C:/Users/mwa/.claude/skills/forge/shared/rules.md (shared forge rules — deliverable-first line 67 authorizes docs/forge writes in plan mode)
- C:/Users/mwa/.claude/skills/forge/forge-grillme/SKILL.md (skill, injected)
- backend/.../security/SecurityFilter.java (verify default-deny claim R5/§Existing Architecture)
- backend/.../resource/OrganisationResource.java (verify D1 generic-GET-leaks-secret rationale)
- docs/forge/spec/2026-07-21-vorstand-admin-section.grill.md (prior grill for format/severity calibration)

## Commands run
- Glob forge skills + docs/forge tree (locate rules.md, prior grills, explorer logs)
- Grep SKILL.md for shared-rules reference

## Observations
- SecurityFilter.java:96 default-deny confirmed; new /mail-settings paths inherit admin-only. R5 mechanism sound. No finding on that.
- OrganisationResource.java:22-27,52-63 GET lists all orgs incl. entries generically → D1 rationale (tag reuse would leak encrypted blob) is factually supported. D1 stands.
- CONTRADICTION: Assumptions L77 (missing key ⇒ save/send disabled, fail-closed) vs Migration L128 (dev DEFAULT key in application.properties). A shipped default means the key is never "missing" → fail-closed never triggers; prod-without-override silently encrypts with a well-known key, defeating R3. → G-001 Blocker.
- D3/L64 says "base64(iv‖ciphertext)" + "Schlüssel aus Konfiguration" but never specifies key length/derivation (raw bytes vs base64 vs PBKDF2) nor nonce source (SecureRandom, 12-byte, uniqueness). GCM nonce reuse under one key = catastrophic. → G-002 Major.
- Risks L155 covers key LOSS (re-enter) but not deliberate ROTATION; no ciphertext/key versioning, no re-encrypt path. Rotation = silent total credential loss. → G-003 Major.
- Risks L157 / Performance L139: sync send, timeout "mitigation" named but no values. jakarta.mail default connect/read timeout = infinite → hung SMTP host pins Quarkus worker thread; test endpoint admin-triggerable to arbitrary host. Not bound to any R. → G-004 Major.
- D2/Alternatives L148: quarkus-mailer dropped as "startup-bound." Partial strawman — ignores MockMailbox test seam + programmatic Mailer. R6/R7 delivery now have NO specified test double. → G-005 Major (testability).
- Goal/R7/NonGoals: infra-first with zero first consumer; MailService.send(recipient,subject,body) single-plaintext-recipient internal contract chosen with no caller — one-way-ish, risks rework; tension w/ rules.md L115 "nothing speculative." → G-006 Major.
- R4/D5 empty=unchanged: no path to CLEAR a stored password (move to no-auth relay / remove wrong cred). R8 "unvollständig" completeness undefined for auth-optional SMTP. → G-007 Major.
- ErrorHandling L120 (missing key ⇒ 500) vs L123 (test always 200 on failure): which on a test send with missing key? success:false conflates config/auth/network; raw SMTP banner in {message} = info/SSRF oracle for internal hosts (tension w/ Security L135 no-leak). → G-008 Minor.
- D1 singleton "Upsert" identity not pinned (fixed _id? findFirst?) → concurrent PUT race can create 2 docs. Low risk (single admin). → G-009 Minor.
- jakarta.mail TLS: mail.smtp.ssl.checkserveridentity default false + STARTTLS downgrade not addressed → SMTP creds interceptable on network path. → G-010 Minor.

## Output
- Review: docs/forge/spec/2026-07-22-mail-settings.grill.md — REVISE, 10 findings (1 Blocker / 6 Major / 3 Minor)
