# Compatibility matrix

Updated **2026-10-12**. Current reconstruction reference: supplied **Access 2016 Runtime package 16.0.4288.1001 / MSACCESS 16.0.4288.1000 x64**, selected explicitly by the user. This is not a claim that the reference is Microsoft's newest Access release.

**Discovery** describes source/library/artifact evidence. **Implemented** describes code in this project. **Local verification** requires recorded tests or observations. **Windows comparison** requires actual Access execution or reopening. These columns must never be collapsed into a single “supported” mark.

Local engine evidence: [fixtures/verification.txt](fixtures/verification.txt), **119 integration assertions passed** in the latest inspected engine run, Java 17.0.18/aarch64, UCanAccess 5.1.8/Jackcess 5.1.7/HSQLDB 2.7.4. Additional object-catalog tests pass **19 assertions with supplied runtime references**, or **9 portable assertions** with runtime checks explicitly skipped. The separate native-design inspector passes **19 assertions** (11 portable bounds/framing and 8 actual runtime fixture checks). Tests use actual native files, not mocked tables. The engine log has conversion warnings for an existing query with duplicate output names: successful execution of its rendered SQL does not prove every query is loaded as an interchangeable internal view. Tests verify a bounded subset, not all data values or database formats. The actual table/query SQL generators and typed UI values pass **28 assertions**; these are backend integration checks, not screenshots or pointer/keyboard interaction proof. Full UI integration/visual checks are tracked separately by the lead.

## Files, data, and schema

| Feature | Discovery | Implemented | Local verification | Windows comparison / limitations |
|---|---|---|---|---|
| `.accdb` open/create | Maintained libraries read/write native files | `openCopy`, native sample creation, picker | Sample/reopened files and external library fixtures open | **Not performed** in Windows; every modern field/format variant not established |
| `.mdb` | Library 2000+ write; 97 read-only | Extension accepted; 97 read-only preflight | No `.mdb` round-trip test recorded | Not performed |
| Preserve original / backup | Explicit working-copy design | Reject existing destination/backup; backup then copy | SHA-256 source/backup checks; collision paths untouched | Copy snapshot during concurrent writer not tested; users should close originals first |
| Native file persistence | Jackcess writes Access pages | JDBC writes + flush/close | DML/DDL/index/relationship close/reopen | No Windows Compact/Repair or Access reopen |
| Tables / fields / keys | Jackcess metadata API | Navigation, column type/nullability/key inspection; basic table design dialog | Native tables, primary key, AutoNumber metadata and UI-generated schema reopen | Comprehensive existing-table design editing not implemented |
| Standard types | Driver mapping for text, numbers, date, Boolean, Currency, etc. | JDBC values and schema display | Unicode/Non-Latin text, null, dates, Currency aggregation, AutoNumber | Decimal precision, GUID, OLE, all sizes/collations remain unverified |
| Datasheet rendering / record operations | Access-style table workflow | Grids and typed primary-key update/insert/delete workflow | Prepared record edit/insert/delete and reopen; complete key required; key changes rejected | UI-generator/typed-value integration passes; pointer/keyboard checks pending; advanced fields remain read-only |
| INSERT / UPDATE / DELETE | Driver DML | SQL mutation on eligible working copies | Insert/AutoNumber, update, delete, reopen | No Windows result comparison; macro side effects not reproduced |
| Create table / index | Driver DDL | SQL commands | Native table and secondary index survive reopen | Basic field/type/required/key table designer verified; comprehensive schema/index editor absent |
| ALTER/DROP / schema changes | Driver advertises broader DDL | SQL gate permits supported table/index DDL | ALTER ADD COLUMN and DROP TABLE examples | Type migration, dependent-object effects unverified |
| Relationships / referential integrity | Native metadata and constraint APIs | Sample relationship; engine checks | Relationship persists; orphan insert rejected | Relationship canvas/editing, cascade update/delete not verified |
| Defaults / validation / input masks | Jackcess supports defaults/rules subset; masks excluded | Engine's available behavior only | AutoNumber tested; rule/default matrix absent | Full validation/error parity not established; mask UI absent |
| Attachments / multivalue / history | Driver/Jackcess have complex-value APIs | Detect advanced fields; force read-only; preserve copied file | Upstream advanced-field fixture detected; mutation refused; read access/source hash verified | No attachment viewer/edit/save, multivalue editor, or history UI; preservation after mutation not claimed |
| Encryption / passwords | Optional encryption codec discovered | Explicit rejection; no codec bundled | Encrypted ACCDB fixture rejected; no working copy/source change | Read/write unsupported; old software password protection not equivalent to encryption |
| Linked tables / external databases | Link APIs exist but copy boundaries matter | Preflight rejects links; blocks external IN SQL | Linked fixture refused before resolution and leaves no working copy | No backend-copy workflow, ODBC/SharePoint/Excel link execution |
| Import/export | Library data import/export APIs | Export native working-copy file; SQL text draft files | Native copy engine behavior tested; UI export proof pending | CSV/Excel import/export absent; `.sql` is not native QueryDef persistence |
| Multiuser / locking | Driver warns of limited multiprocess behavior | Local single-working-copy workflow | No concurrency test | No shared-file multiuser parity claim |
| Unsupported objects / metadata | Real runtime forms/modules/report catalog and storage inspected | Read-only object inspector; engine forces read-only for application-container children/opaque behavior | Nine queries survive DDL; 66 forms/34 modules and 726 storage rows retain metadata/blob fingerprints after direct Jackcess table addition on runtime copy | This isolated fingerprint check is not engine write eligibility or Windows execution; macros and general form/report save/round-trip remain unverified |

## Queries and SQL

| Feature | Discovery | Implemented | Local verification | Remaining limitation / Windows comparison |
|---|---|---|---|---|
| SELECT / filter / order | Driver Access SQL translation | SQL editor, Run, result grid | Count, filters, ORDER BY, 5,000-row cap + explicit truncation | Reference result comparison absent |
| Joins / GROUP BY / HAVING | Driver support | SQL execution | Join/money aggregate/group/HAVING example | Nested outer joins and all duplicate/null cases unverified |
| Subqueries | Driver support | SQL execution | `IN (SELECT ...)` example | Correlated/EXISTS/scalar cases not verified |
| DISTINCT / UNION / TOP | Discovery in engine/dialect | Passed to engine when admitted | No dedicated assertion for each in this log | Do not infer coverage from one saved SELECT |
| Nulls / calculated fields | Driver functions | Results retain null; expressions | `Nz`, `IIf`, `Left`, `&`, null/Unicode | All coercion and eager/lazy error behavior unverified |
| Dates / functions | Access literal/function translation | SQL execution | `#date#`, DateDiff, field comparison | Locale/timezone/precision and ambiguous dates need fixtures |
| LIKE wildcards | Driver accepts both alphabets | SQL execution | Access `*` example | More permissive than some Access modes; `?`, ranges, `#`, ANSI-92 cases not compared |
| Existing native saved queries: enumerate / SQL | Jackcess reads definitions | Lists names + rendered SQL; pass-through marked unavailable | Nine native definitions; existing SELECT text | Parameter/action/crosstab/passthrough object semantics not all executed |
| Existing saved query execution | UCanAccess can expose views | Execute displayed native SELECT SQL | Existing upstream SELECT definition runs | Automatic view load warning retained; not every view successfully materializes |
| Native saved query creation / editing | High-level writer absent; CREATE VIEW fails; experimental catalog writer added | Explicit experimental one-local-table SELECT creation; broad editing unavailable | Native query survives file/JDBC reopen, preserves nine existing definitions; verified-backup failure/restore tests | No Windows QueryDef reopen; joins/UNION/TOP/PARAMETERS/action query serialization unavailable; drafts separate |
| Parameter queries | JDBC binding and Access PARAMETERS dialect | Engine named/positional parameter planning and bound execution; UI integration ongoing | Named declaration, repeated names, typed coercion, positional binding and injection-safe value tests | Form references, all Access types and prompt behavior not compared in Windows |
| Crosstab / TRANSFORM | Library advertises support | No dedicated designer | No executed crosstab assertion | Needs native/result reference fixtures |
| INSERT…SELECT / general action cases | Driver DML discovery | SQL execution when supported | Simple INSERT/UPDATE/DELETE only | Bulk/action totals and rollback semantics not established |
| Make-table SELECT…INTO | Driver direct prototype failed; independent translation added | Unparameterized native make-table translation | Rows appear in new table and survive close/native reopen | Parameterized make-table explicitly unavailable; rollback/complex projection cases unverified |
| Pass-through / remote SQL | Native query object readable | Flag preserved; refuse external execution | Pass-through marking verified | No server connection or remote SQL engine |
| Transaction/savepoint / rollback | Driver supports APIs | Single-command workflow | Native writer interrupted-backup/post-mutation verified-restore tests pass; general transaction/crash tests absent | Crash-safe recovery requires additional fault tests |
| SQL command safety boundary | Application gate | One statement; deny internal/system/external/Java commands | Quoted literals/bracket IDs accepted; external/internal/multi-statement writes rejected | Gate acceptance is not full Access syntax compatibility |

## Application objects and interface

For complex objects, “discover” can mean a resource/API clue; “open” means this app can interpret the object; “render” means it draws it correctly; “design” means it edits the definition; “execute” means its behavior/events run; “save” means native compatible persistence. Each stage requires its own evidence.

| Object / workflow | Discovery | Open / render | Edit design / execute | Native save | Verification |
|---|---|---|---|---|---|
| Tables / schema UI | Metadata; observed NavPane strings | Lists tables; grids/schema inspection | Basic new-table designer and ADD COLUMN; advanced existing-table design unavailable | DML and basic DDL yes | Engine tests; UI comparisons pending |
| SQL query UI | Access2016 resource IDs + SQL APIs | Independent SQL editor/results | Run supported SQL; draft editing | Restricted experimental native SELECT creation | Engine writer/reopen and recovery tests; no Windows comparison |
| Query Design View | Query-tools tabset token | Basic structured query dialog | Projection/filter/sort/aggregate and one equality join generate SQL | Restricted experimental native SELECT writer separately | 28 UI-generator assertions; no Access graphical canvas parity |
| Relationships window | Native relationships; tool-tab token | Unavailable | Unavailable | SQL/native library subset only | One native relation/RI tested, no window parity |
| Forms | 66 actual runtime wizard form entries observed | Native catalog and experimental flat property/control-candidate inspection; rendering unavailable | Unavailable; no event runtime | Unavailable | Actual form249 properties/13 named control candidates framed with offsets; no semantic/render reference |
| Reports / print layout | Actual native runtime report observed | Native catalog and experimental flat property inspection; rendering unavailable | Unavailable; no pagination/event engine | Unavailable | Actual report five segments/one ActiveX control candidate; no printing/render comparison |
| Macros / data macros | Conventional macro type candidate; UCanAccess ignores data macros | Catalog candidate inspection; no macro fixture yet | Unavailable | Unavailable | Opaque user-table payload/application-container guards tested synthetically, not decoded real data macros |
| VBA / modules | Actual 34 compiled runtime modules + VBE7 | Native catalog inspection; source/p-code decoding unavailable | Unavailable | Unavailable | Catalog/storage fingerprints tested; no interpreter, Access object model, COM events or execution |
| `.accde` / `.mde` applications | Compiled deployment formats | Read-only catalog inspector can inspect supplied ACCDE independently; main database UI suffix support separate | Execution not supported | Not supported | No source recovery or rendering claim |
| Windows integrations / COM / ActiveX / Office automation | Static DLL imports and documented deployment boundary | Not supported | Not supported | Not supported | Requires replacement or Windows execution; no Mac equivalence |
| Ribbon / navigation / object tabs | REA UI-resource inventory | Independent Swing workspace implemented | Relevant supported controls only; unavailable features labeled | N/A | Resource-name evidence only; no matched Windows screenshots/DPI proof |
| Property sheets / wizards / dialogs | Some resource clues | Only ordinary app/file/error dialogs | Access property sheets/design wizards absent | N/A | No parity verification |
| Accessibility / shortcuts | Swing capability, conventional Mac keys | Keyboard shortcuts/focus in code | Full VoiceOver/focus QA pending | N/A | Not yet accessibility-verified |
| macOS app / Open With | Java jpackage mechanism | Packaging implementation in progress | Bundled runtime + association require integrated QA | N/A | Apple Silicon integration lead-owned; Intel unverified |
| Original Windows Access on macOS | Extracted x64 Windows binary; no installed compatibility runtime | Not run | Not run | Not run | REA static evidence does not establish execution |

## Release claims and next checks

The present app is an initial native-file SQL/database milestone with major Access authoring/application-object gaps. It is not full Access parity. The broad goal remains table/query/relationship designers, imports/exports, forms/reports/macros, and tested native preservation.

Before expanding write eligibility, obtain native fixtures containing forms, reports, macros, modules, validation rules/defaults, uncommon collations, complex fields, and encryption. Compare original and mutated copies in Windows Access: object inventory, SaveAsText exports, execution/printing, data results, and Compact/Repair behavior. Keep advanced files read-only or refused where supported mutations cannot yet be proved safe. Do not label any Windows check passed until actual evidence is saved.

## 1.0.2 direct opening and runtime regression

Normal Open edits the selected native file, with verified automatic content-addressed recovery snapshots. Explicit Open a Copy remains available and preserves its original/copy isolation. Reopening unchanged files reuses snapshots rather than creating more user-facing working filenames. Both ACCDB and MDB direct persistence, backup reuse/refusal, and unsupported-file read-only behavior have regression coverage.

The packaged runtime includes `java.scripting`; generated MDB default expressions and validation metadata are exercised by every installation self-check. Full-JDK-only checks are insufficient because jpackage can omit lazily loaded modules. The package and installed application passed the generated-expression MDB proof and private supplied-MDB opening checks. SQL validation failures can invalidate a UCanAccess backing channel; general transactional recovery after driver failures remains a separate limitation.
