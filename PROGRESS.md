# Lattice progress

Updated: 2026-10-13.

## Verified checkpoint

- Java17/aarch64 native Access data engine uses pinned UCanAccess5.1.8, Jackcess5.1.7 and HSQLDB2.7.4. The local full regression run passes **198 assertions**:132 database,28 UI generator/typed-value,19 object-catalog and19 native-design checks with actual Microsoft runtime fixtures.
- Working copies and original backups preserve source bytes. Advanced/application behavior is read-only where detected; linked/encrypted files are refused. Restricted experimental native SELECT creation has native reopen and verified-backup recovery checks; Windows Access verification remains absent.
- Blank native ACCDB creation is implemented and verified through create/reopen, preservation and refusal checks. New Database is available from File and the welcome screen, followed by a Create First Table action. Basic table design, add-field, query builder, typed parameters and primary-key record editing are implemented. These are limited independent workflows, not the complete Access feature set.
- The supplied Access2016Runtime package is identified/extracted statically: package16.0.4288.1001/x64, MSACCESS16.0.4288.1000/x64. Genuine runtime wizard forms/modules/report fixtures were inspected. REA_FINDINGS.md records exact hashes, offsets, observations and limitations. No Windows executable was run.
- The earlier direct-JAR dependency issue was fixed and the user confirms the packaged app opens. No particular user database's full contents or application execution have been independently verified.

## Current design work

The product name is **Lattice**. The redesigned workspace preserves Access command categories at the top, searchable object navigation at the left, and object tabs/datasheets in the center. The neutral compact interface, responsive controls, SQL line numbers and original interwoven lattice icon are implemented and packaged. Rendered normal/hover/pressed/disabled controls and large/small icons were visually inspected. Supplied design guidance and screenshots are retained locally and excluded from public history. DESIGN.md records HIG sources and implementation criteria.

Delivered `build/mac/Lattice.app`, `build/mac/Lattice-1.0.0-arm64.zip`, and `build/mac/Lattice-1.0.0.dmg`. The bundled executable passed native database creation, SQL, prepared writes, native reopen and source-preservation verification. The ZIP was unpacked and its ad-hoc signature verified; the DMG checksum passed `hdiutil verify`. The lead inspected the running Lattice workspace at 1240 by 820 logical pixels: ribbon controls, object tabs, sidebar and SQL line numbers were visible and readable. The existing user database was not modified during this visual check. Keyboard-only workflows and full accessibility remain unverified. No native Liquid Glass or complete AppKit material implementation is claimed by this Swing/Aqua interface.

## Remaining parity gaps

Forms/reports can be inventoried and selected stored records structurally inspected; they cannot render or execute. RecordSource mapping, control nesting/defaults, VBA/p-code/Access object model, macros, event dispatch, report pagination and ActiveX remain substantial unfinished work. The actual barcode report contains Windows ActiveX configuration. Native preservation tests are bounded record/fingerprint checks, not Windows execution or general save compatibility.

Windows reference SQL/results, SaveAsText semantic pairs, design/render/print comparisons and native Access reopen are still required. Linked data, encryption, shared-file multiuser behavior, comprehensive schema/query designers, Intel packaging, dark mode and VoiceOver have no complete parity verification. `.accdb` support is demonstrated for tested fixtures; it is not a guarantee that every Access application works.

## Refinement and public source

The 2026-10-13 refinement preserves the database API and adds explicit command labels, distinct icons, contextual enabled states, keyboard focus feedback, persistent sidebar selection/disclosure and search shortcuts. The full regression run passed 198 assertions (132 database, 28 UI value/query generation, 19 catalog, 19 design framing). A later sidebar-only edge repair has a separate scratch interaction-state check; The final 1.0.1 package was opened through macOS UI automation: practice database creation, distinct native working-copy opening, visible SQL execution and two grouped result rows were observed. The welcome and SQL/results screens were inspected at 1240 by 820 logical pixels; database/query command enabled states and labels were readable. The search shortcut did not produce observable input through the automation bridge, so real keyboard search remains a manual QA item. Raw logs remain local.

Public source repository: https://github.com/Loomy00/lattice. Binaries, personal database files, supplied reference material and raw machine-specific logs are excluded. `LEGAL.md`, `NOTICE`, and `licenses/THIRD-PARTY.md` describe publication boundaries and dependency obligations. First-party license terms remain unselected. Public installer distribution is pending bundled-runtime corresponding-source compliance.

Local 1.0.1 packages include the updated notices and passed installation self-verification, app/ZIP signature checks and DMG checksum verification. These packages remain local until the runtime redistribution arrangements in LEGAL.md are complete.
