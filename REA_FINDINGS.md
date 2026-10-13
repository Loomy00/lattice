# Supplied Access Runtime: REA findings

Inspected **2026-10-12**, without executing the installer or any extracted Windows component. The user selected this artifact as the present project reference. This report concerns **Access 2016 Runtime**, not the latest Microsoft 365 authoring application.

## What was actually recovered

| Artifact | Observed identity | SHA-256 |
|---|---|---|
| User-supplied `a2016rt64.exe` | 330,879,104 bytes; PE32 x86 installer wrapper | `a62b4e6dbaa1ace71b992877fa053c654f8cb568c08d38fcccaa5e0dbcb1870c` |
| Embedded outer CAB | Offset 379,392; 330,481,197 bytes; 41 entries | `4fa613ae9ff9856f5e26879ef361545eb6462e8834fe59ca0e2443a8cb383c1b` |
| Extracted `accessrt.cab` | 220,381,841 bytes; 649 file records, 15 folders | `0bbc2861cff5bfaacb1748f470bd1a178e10685e1295e4f00d2ee95418123e93` |
| Extracted `MSACCESS.EXE` | 20,663,976 bytes; PE32+ x86-64 GUI executable; FileVersion/ProductVersion **16.0.4288.1000**, ProductName Microsoft Office 2016 | `399e59b935f99fe0d3dc7647be02c583e2ca56abed23ae7a9720af49f1a7c76b` |
| Extracted `ACECORE.DLL` | 2,281,576 bytes; PE32+ x86-64 Windows DLL | `0f8f87883b9e53f17793ff71c46c85df3069ba6939da28e3f9a63c01d56f9bdd` |
| Extracted `ACEDAO.DLL` | 595,200 bytes; PE32+ x86-64 Windows DLL | `cb4f3471f04b00a78f80be7b14d1be34b21460df9b848694f04df25f83a54cd9` |

The wrapper's embedded `SCRIPT` XML names **Microsoft Access 2016 Runtime** and queues the runtime MSI/CAB packages before launching `setup.exe`. Extracted `accessrt.xml` and `accessrtww.xml` declare `Platform="x64"`, `MSIVersion="16.0.4288.1001"`, product code `{90160000-001C-0000-1000-0000000FF1CE}`. Package version and executable version differ; both are retained. Wrapper bitness is not payload bitness.

The wrapper manifest identifies an x86 patch/install engine requesting administrator privileges. These are static declarations; no Windows installation, prompts, elevation, registry changes, or application startup were observed.

## REA evidence ledger

| Evidence | Operation and observation | Coverage limit |
|---|---|---|
| `ev_bd4b42b4855cabd16a5133bd8d26b69a169a0f1912f8213f3c487fb6a5d465f3` | `inspect_pe_resources` on supplied installer; complete bounded resource tree: **31 entries / 13 resources**, including SCRIPT, version blocks, and manifest, with exact file ranges/hashes | Payload contents generally opaque to REA; XML/version interpretation below used normal read-only parsers |
| `ev_5f4444e12690e4a8c7caac15cbdac90aa060f04d8f24b83b0f3023eab23e4c30` | `inspect_pe_resources` on extracted MSACCESS; **2,168 entries / 1,077 resources**, machine 34404 (`0x8664`), including **74 UIFILE** resources | Static inventory, not decoded rendering, Windows language fallback, screenshots, or executed feature behavior |
| `open_binary` identity results | REA independently identifies wrapper x86 and extracted MSACCESS x86-64, with matching hashes | Opening a target is not decompilation or execution |

The connected tool session was agent-scoped. Research used its own session and closed it cleanly. Ghidra was available, but no deep native analysis was needed to answer this package/version/dependency question. No decompiled function, observed runtime call, or whole-application source recovery is claimed.

Complete retained evidence and intermediate files are local under `../../work/access-runtime/`: `installer-rea-evidence.json`, `msaccess-resource-evidence.json`, `msaccess-static-imports.json`, `accessrt-cab-inventory.tsv`, `accessrt-cabextract-inventory.txt`, decoded installer script/manifests, CABs, and the three focused binary targets. The `msaccess-rea-evidence.json` export is empty because opening the new target reset the session ledger; use **msaccess-resource-evidence.json**, which preserves the complete original returned Evidence record. No Microsoft binaries/assets are bundled into the independent app.

## Extraction method and integrity limits

Read the CAB header at the measured overlay offset, carve its declared range into a working file, and extract it with macOS `bsdtar`. The nested Access CAB uses LZX compression and a reserved header; `bsdtar` rejected it. A **local** build of cabextract 1.11, fetched from [its project distribution](https://www.cabextract.org.uk/cabextract-1.11.tar.gz), successfully listed it and extracted MSACCESS/ACECORE/ACEDAO and later two focused Access wizard databases. The helper/build logs remain in work; nothing was installed globally.

cabextract warns of **17,168 trailing bytes** after the inner CAB's declared size. Signature/trailer interpretation was not verified. The full extracted CAB's MD5 equals the package XML's declared `37463B6AA1D2474F65024C45B6ABEFC0`; this corroborates extraction consistency, not signature authenticity or security. Windows Authenticode trust was not validated. Cabinet file timestamps are recorded metadata, not independent proof of build dates.

## Useful UI clues

Source-backed resource strings were indexed in `ui-resource-string-index.json`; raw selected resources are `ui-resources/*.bin`.

- UIFILE **395** contains `SegoeUI` and an Access designer status-border token.
- UIFILE **406** contains `NavPaneStyle`, `NavPaneShutter`, `NavPaneGroup`, `NavPaneGroupHeader`, `NavPaneItem`, `NavPaneContentScrollViewer`, and navigation context-menu tokens.
- UIFILE **494/495** identifies ribbon customization lists, command wells, `CustomizeRibbon`, `CustRibbon`, and display-navigation options.
- UIFILE **600** contains `AccessScratch.ribbonx` markup and tabset identifiers for report, relationship, query, datasheet, and table-design tools. This particular start-from-scratch resource marks those tabsets hidden; it does not prove their normal runtime visibility.

Most UIFILE payloads are compiled binary with embedded strings, not straightforward XML layouts. The observed names can guide independent UI reconstruction. Exact dimensions, colors, focus behavior, availability rules, DPI scaling, and rendered typography remain unmeasured. Embedded PNG/icon inventory does not supply permission or a reason to ship copied Microsoft branding.

## Execution architecture and what REA cannot supply

A small source-byte PE parser inspected MSACCESS's ordinary import directory (RVA/file mapping preserved in the JSON). Observed imports include **KERNEL32, ADVAPI32, GDI32, gdiplus, MSIMG32, WS2_32, Cabinet, VCRUNTIME140, MSVCP140, Windows CRT API sets, and Office `oart.dll`**. Imports demonstrate Windows/Office link dependencies. Their presence does not prove which functions run, network usage, or that this list covers delay-loaded/dynamically loaded modules.

The CAB also lists `VBE7.DLL` and numerous Office resources. Its presence does not establish that all VBA host/object-model behavior can be independently reproduced or that authoring features are enabled. Microsoft identifies the runtime as a deployment environment with design UI removed or disabled. [Microsoft runtime description](https://www.microsoft.com/en-us/download/details.aspx?id=50040), [runtime deployment limits](https://support.microsoft.com/en-us/access/deploy-an-access-application) (accessed 2026-10-12).

On this Apple Silicon host, bounded executable/application checks found **no existing Wine, CrossOver, Whisky, Parallels, VMware, or UTM environment**. Rosetta is installed, but it does not implement Windows APIs. No original Access-on-Mac execution was attempted or verified. Running it would require a compatible Windows environment or separately proven Windows compatibility layer, its dependent components, and a disposable Access application fixture. Neither static extraction nor native pseudocode turns Windows DLLs into a macOS application.

## Follow-up targets

1. Continue independent Java database/UI implementation using these Access 2016 resource clues. Preserve broad parity goals and flag absent workflows.
2. For a concrete unresolved native question, use the extracted MSACCESS or ACECORE identity as a focused REA target; trace one known feature boundary rather than decompiling every function.
3. For application execution parity, run the supplied runtime on a Windows reference machine with fixtures containing forms/reports/VBA/macros, and record outputs/events/printing.
4. For SQL editing, table/query design, and broader authoring parity, inspect **full Access**, since this runtime does not expose a complete authoring environment. Export objects using Access's documented `SaveAsText`, and compare rendering plus save/reopen behavior.

Status: actual installer, payload identity, resources, version declarations, focused extraction, and static dependency boundary **established**. Runtime behavior, authoring parity, full native object decoding, Windows round-trip verification, and original-program macOS execution **unresolved**.


## Native application-object checkpoint

The supplied runtime CAB also contains genuine Microsoft application fixtures, inspected read-only rather than executed:

| Fixture | SHA-256 | Actual catalog/storage observation |
|---|---|---|
| `ACWZMAIN.ACCDE` (9,277,440 bytes) | `f4269a5d241c855f3bf0b217caa0740f4bdc487b226c4c9cd57a2ff3a02aa0c3` | 66 forms (Type −32768), 34 modules (−32761), 726 MSysAccessStorage rows; Jackcess identifies V2007/VERSION12 |
| `ACWZUSR12.ACCDU` (3,424,256 bytes) | `6cef0dbb89403c2ea19f8838ab8f6f6bba4c18998704098ce06ca65478f24ddd` | Native report `mlbl_BarCodeTemp` (−32764) under Reports |

`AccessObjectCatalog` now exposes immutable object inventory, table/field property maps, storage metadata and blob lengths/SHA-256 fingerprints without resolving external links. Nineteen assertions pass with these optional runtime fixtures; nine portable assertions pass without them. A disposable runtime copy retains all 100 application entries and all 726 storage metadata/fingerprints after an unrelated direct Jackcess table addition. This proves the compared records were preserved in that operation; it does not prove Access execution, authoring, engine write eligibility, or every byte in a file. Application-container children force read-only operation in the ordinary SQL engine.

`NativeDesignInspector` adds an independently implemented experimental flat record decoder. Format research used [pyOpenVBA source commit 906ad9321e6195d915a9c84d25759981c9a5f70f](https://github.com/WilliamSmithEdward/pyOpenVBA/blob/906ad9321e6195d915a9c84d25759981c9a5f70f/src/pyopenvba/access/_designs.py) and its [native directory source](https://github.com/WilliamSmithEdward/pyOpenVBA/blob/906ad9321e6195d915a9c84d25759981c9a5f70f/src/pyopenvba/access/_storage.py), accessed 2026-10-12. The project is MIT licensed; its broad codec/execution claims are upstream claims, not verification of this Java app. Its parser assumes property IDs increase within a segment, but this actual 2016 form changes ID419 to271 at byte1128. Our decoder preserves that valid record and validates framing/bounds rather than imposing that assumption.

The Forms directory `\x03DirData` maps `af_frmAutoformat` to numbered folder0. Its Blob row54 is **6,573 bytes**, SHA-256 `9d58c9d558128ec9c97404ffde9f297c975281109605ef5ba67e8e77bdccfa05`: 249 property records, 20 marker boundaries, 21 flat segments, closing marker at byte6569. The caption candidate `AutoFormat` is property code17 at byte495; `btnOK` is name code20 at byte1838. Thirteen named control candidates exclude unnamed defaults and the section. Strings are decoded only from length-delimited type10/12 records using strict UTF16LE; there is no blanket string scrape, fabricated tree or renderer. Each property carries exact source/value byte offsets, length, numeric type/code and raw-value SHA-256.

The report Blob row26 is **5,741 bytes**, SHA-256 `35bca9f670dcbd33f6bf744d1b3d462644c6f4e5927b0fd9f0ea4f4592f952c9`: five segments, one named CustomControl candidate, structurally complete through byte5737. Its property at byte5545 names `Microsoft BarCode Control 9.0`; byte5621 names `BARCODE.BarCodeCtrl.1`. This is concrete stored ActiveX configuration, not proof the component loads. Some decoded report strings preserve mojibake-looking content: decoding bytes correctly is not evidence of the intended localized display.

Nineteen native-design assertions pass (eleven portable bounds/framing tests plus eight actual fixture checks), retained in `../../work/native-design-verification.txt`. Both fixture source hashes remain unchanged. Metadata scanning projects only Id/ParentId/Name, then fetches the selected directory and Blob. Inspection caps 20,000 records and 16MiB combined retained payload. Jackcess's public API materializes one selected long value before exposing its length; the budget cannot prevent that initial library allocation on an oversized/corrupt value. Process isolation or a separate bounded native-page reader remains necessary for a hard untrusted-input memory guarantee.

**Exact unresolved decoding/execution boundaries:** no paired Windows SaveAsText export establishes RecordSource property mapping; these two objects do not yield a verified RecordSource. Group/section/attached-control nesting and defaults are not fully validated. TypeInfo members, code-behind module linkage, compiled VBA/p-code/source recovery, Access expression semantics, macro actions, event dispatch, report pagination and ActiveX behavior remain unimplemented. The original Windows executable requires the Windows/Office ABI and dependent components; a Java parser does not provide those. [Microsoft SaveAsText](https://learn.microsoft.com/en-us/office/client-developer/access/desktop-database-reference/application-save-as-text) is the practical route to paired semantic fixtures, followed by actual Access rendering/execution and native reopen comparisons.

The source-backed compatibility-layer assessment also remains unfavorable to a guarantee: CodeWeavers' [runtime2016 entry](https://www.codeweavers.com/compatibility/crossover/microsoft-access-2016-runtime) says installs/will not run, last tested19.0.1, explicitly flagged outdated. Its [fullAccess2016 entry](https://www.codeweavers.com/compatibility/crossover/microsoft-access-2016) lists installs/will not run at26.2.0. These ratings are not local tests or proof every configuration fails. No portable Wine installation or runtime execution was attempted. Windows reference execution is still the concrete route to full behavior evidence.
