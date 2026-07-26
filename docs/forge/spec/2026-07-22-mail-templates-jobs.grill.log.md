# Grill work log — 2026-07-22-mail-templates-jobs
- Task (verbatim): grill docs/forge/spec/2026-07-22-mail-templates-jobs.md (adversarial review, write .grill.md + .grill.log.md)
- Started: 2026-07-22

## Files read
- docs/forge/spec/2026-07-22-mail-templates-jobs.md (target, full)

## Files read (claim checks vs spec §Existing Architecture)
- backend/.../service/MailService.java — confirms send(String,String,String) plaintext-only, singleton sender, MailException categories. Spec accurate.
- backend/.../entity/MailSettings.java — confirms SINGLETON_ID hard singleton + upsert. Spec accurate.
- backend/.../security/SecurityFilter.java — confirms default-deny, admin passes, non-admin whitelist incl. GET /organisation/groups. New /api/v1/* paths admin-only for free. Spec accurate.
- backend/.../entity/Person.java — confirms familyId + findByFamilyId + basicProperties FieldRefs. Accurate.
- backend/.../resource/PersonResource.java — isChild + resolveBasicValue are PRIVATE, N+1 (FieldDefinition.findById + inst find per ref). resolveSemesterId = newest by createdAt. patchGroup stores semester_assignments {definitionId, fieldInstanceId}. No isParent helper. No parents-by-group endpoint.
- backend/.../service/BilanzCalculationService.java — groupAssignment keys on fieldInstanceId. Accurate.
- backend/.../entity/FieldDefinition.java — NO section/category field; findActive() returns ALL active defs.
- backend/.../migration/FieldDefinitionSeedMigration.java — seeds firstName..address + role/personType enums + cookingDuty + 6 food-property, all in field_definitions; address & cookingDuty are type:object.
- backend/.../migration/GroupInstanceMigration.java — groups are FieldDefinitions fieldName="group" w/ shared field_instance (groupInstanceId).
- backend/.../resource/OrganisationResource.java — NO /groups handler; served by getByTag(tag) → OrganisationDTO.definitions = resolved FieldDefinitions (definitionId+label). Does NOT return groupInstanceId.
- backend/pom.xml — no quarkus-scheduler/quartz (D4 correct); quarkus.platform.version=3.36.1 (spec correct).

## Commands run
- Glob **/MailService.java etc. (paths are backend/src/main/java/at/kigruapp/...; spec cites repo-relative w/o backend/ prefix — cosmetic)
- Grep scheduler|quartz in pom.xml → none (confirms "no scheduling present")
- Grep /organisation/groups → only SecurityFilter whitelist; endpoint is getByTag("groups")

## Observations → findings
- groups endpoint returns definitionId, resolver joins on fieldInstanceId (groupInstanceId) → G-001
- findActive() no basic-property filter; address compound object → G-002
- ngx-quill emits class-based HTML, no CSS inliner → G-003
- no concurrent-run guard → G-004
- cron standalone validation (inactive job) mechanism unspecified → G-005
- cron timezone unspecified → G-006
- sendHtml signature has no sender param, contradicts D8 → G-007
- resolveBasicValue private + N+1 vs batch-load claim → G-008
- empty recipient set outcome undefined → G-009
- deleted/outdated group ref silent zero → G-010
- missing-template decision unmade (OQ5) → G-011 (minor)

## Output
- Review: 2026-07-22-mail-templates-jobs.grill.md — Verdict REVISE, 11 findings (1 Blocker / 6 Major / 4 Minor)
