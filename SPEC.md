# Access for Mac — product specification

Date: 2026-10-12. Independent compatible application; not a Microsoft product.

## Goal and reference

Build a reliable macOS desktop application for real Access databases and Access SQL practice, progressively expanding toward the workflows and appearance of Microsoft Access. The original project brief is retained locally and excluded from public history. It remains the product goal; the initial release is not full Access parity.

The user explicitly selected the supplied Access 2016 package as the project reference on 2026-10-12. Target its behavior and familiar 2016 workspace instead of pursuing the newest Microsoft 365 release. The package identifies Microsoft Access 2016 Runtime, version 16.0.4288.1001, x64. Its runtime designation and actual age remain accurately recorded; this choice does not relabel it as the newest Microsoft release. Full SQL authoring workflows may require independently implemented tools because the runtime package does not establish the full retail authoring interface.

## Current architecture decision

Use Java 17, Swing, UCanAccess and Jackcess for the first working macOS milestone. The installed development JDK is Azul Zulu 17.0.18 on Apple Silicon. Pin dependencies and include them with a bundled runtime in a macOS application image. SQL execution is local and requires neither AI nor a subscription. This decision depends on passing native ACCDB persistence tests.

UCanAccess translates Access SQL into a temporary HSQLDB representation and writes through Jackcess to the native Access file. The temporary SQL engine and the saved ACCDB must be documented separately. Passing a query through this engine does not establish equivalent Windows ACE behavior.

Swing offers a practical installable desktop baseline with native window controls and file dialogs. The user's updated design requirement is Apple macOS styling with the familiar Access spatial layout: grouped commands above, object navigation left, and object tabs/datasheets/SQL in the main workspace. Use the Apple HIG-informed plan in `DESIGN.md`; the later styling instruction takes precedence over an exact Windows skin. Rendering, keyboard behavior and accessibility still require actual QA.

## Safety and persistence

- Opening a user database creates a distinct working copy; never edit the original.
- Refuse accidental overwrite of a working file. Display original and working paths.
- Treat linked databases, encryption, unsupported complex objects and concurrency as explicit compatibility questions. Do not infer safety from successful table listing.
- SQL edits persist in the working ACCDB. Exporting a database means copying that native file, not converting it to CSV or SQLite.
- Preserve the copied original as recovery evidence. Surface failures; do not silently report an operation as saved.
- Unsupported forms, reports, VBA and macros must not be presented as executable. Preservation after mutations must be tested on representative original Access fixtures before claiming support.

## Milestone acceptance

1. **Feasibility:** exact dependency versions and licensing recorded; disposable real ACCDB created/opened; Access SQL results asserted; original-copy isolation and close/reopen persistence tested; Windows checks explicitly separate.
2. **Usable desktop:** buildable and packaged Apple Silicon app; database opening; table and field navigation; SQL editor with results/errors; sample practice database; saved queries where genuine native persistence is proven; explicit safe-copy workflow.
3. **Design tools:** editable table design, indexes and relationships, query designer, import/export, validation and defaults with dedicated regression fixtures.
4. **Application objects:** discover and preserve, then render/design/execute/save forms and reports as individually verified capabilities; macros and VBA require their own compatibility work.
5. **Release/parity:** reference screenshots, broader ACE comparisons, recovery and performance, accessibility, signing/notarization and Intel builds.

## Verification boundaries

Keep discovery, implementation and verification separate in `COMPATIBILITY.md`. Engine tests against generated fixtures demonstrate that engine's behavior, not Windows Access equivalence. The supplied installer can reveal packaged structure and static resources; static inspection cannot establish runtime behavior. Do not execute or install it as part of passive analysis.

## Team

Three GPT-6.1 Sol agents at High effort handle research/REA, database implementation and desktop implementation. The lead coordinates interfaces, reviews integration, verifies packaging and records status. No recursive delegation and no duplicate ownership.


## Current product/design direction (2026-10-12)

The product name is **Lattice**. Deliver a polished Apple-esque Mac interface and an original distinctive lattice logo while retaining Access's top command categories, left object navigation, central object tabs and datasheets/SQL workspace. Supplied design guidance and reference screenshots were consulted locally and are excluded from public history; `DESIGN.md` links official Apple guidance. Adapt its principles to Swing/Aqua without expensive simulated glass, unsupported native Liquid Glass claims, or a major shell rewrite for appearance alone. Preserve all verified database protections and explicit native-object execution limits.
