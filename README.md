# Lattice

Lattice is an independent macOS desktop workspace for Access database files and local SQL. It supports native `.accdb` and `.mdb` working copies, editable datasheets, basic table and query design, and a familiar ribbon/sidebar/tab layout. The compatibility reference is Access 2016. Complete Microsoft Access parity remains a development goal.

<img src="docs/brand/Lattice.png" alt="Lattice icon" width="128" height="128">

## Build and run

This repository publishes source, build scripts, dependency checksums, and license notices. Installers are built locally with `./scripts/package-macos.sh dmg`; that creates a `.app`, ZIP and DMG under `build/mac/`. The package includes a Java runtime and libraries. The application is a developer preview with ad-hoc signing.

Requires a Java 17 JDK on macOS. Set `ACCESS_JAVA_HOME` if the build cannot locate your JDK. Downloaded dependencies are verified against `dependencies.lock`; binaries and personal database files are excluded from Git.

```sh
./scripts/fetch-deps.sh
./scripts/build.sh
java -jar build/access-for-mac.jar
```

If the dependency folder is missing, run `./scripts/fetch-deps.sh` while online. Subsequent builds and database use are offline.

## Use safely

1. Open an `.accdb` or supported `.mdb`, then choose a new working-copy filename. Existing files are not overwritten.
2. Expand Tables or Queries in the navigation pane. Double-click a table to view records and its field definitions.
3. Open a SQL Query tab, enter one statement, and use **Run** or **⌘Enter**. You can run selected SQL text. Parameterized queries prompt for typed values.
4. SQL edits affect the working copy. **Export Working Copy** creates another native Access database file. It does not replace the original.
5. A sibling file ending in `.original-backup` retains the original bytes. To recover, close the app and copy that backup to a new `.accdb`/`.mdb` filename; keep the backup intact.

Use **New Database…** to create a blank native Access database, or **Practice Database…** for disposable exercises with Customers and Orders, a relationship, dates, currency, Unicode and NULL values.

## Practice

```sql
SELECT FullName, City FROM Customers ORDER BY FullName;
```

```sql
SELECT City, Count(*) AS CustomerCount
FROM Customers
GROUP BY City;
```

```sql
SELECT c.FullName, Sum(o.Amount) AS Total
FROM Customers AS c INNER JOIN Orders AS o
ON c.CustomerID = o.CustomerID
GROUP BY c.FullName
HAVING Sum(o.Amount) > 100;
```

```sql
SELECT FullName, Nz(Notes, 'No notes') AS NotesShown
FROM Customers
WHERE JoinedOn >= #2026-01-01#;
```

```sql
UPDATE Customers SET City = 'Durban' WHERE CustomerID = 3;
```

## Current boundaries

- Native `.accdb` data and schema changes through supported SQL are tested on disposable fixtures. The results have not been compared with a running Windows Access instance.
- Native saved-query creation has an **experimental**, restricted one-table SELECT writer with backup/recovery and native reopen checks. Joins, UNION, TOP, PARAMETERS and action-query saving are not supported by that writer. It has not been verified in Windows Access. Ordinary SQL drafts can also be saved as separate `.sql` files.
- Named `PARAMETERS`, positional bindings, and basic `SELECT INTO` are implemented. The make-table translation is deliberately limited; unsupported forms report an error.
- Basic table/query design tools and primary-key record editing are implemented and pass integration checks; their exact current validation is recorded in `PROGRESS.md` and the compatibility matrix. The complete Access design feature set remains unfinished.
- Forms, reports, macros, VBA, Windows integrations and multiuser editing are not implemented. Do not assume an Access application will run because its tables open.
- Linked tables are refused before resolution. Encryption/password-protected files are unsupported. Advanced fields, application objects and opaque table behavior trigger read-only mode where detected. Native object inspection and experimental form/report record decoding do not imply rendering or execution.
- Results display at most 5,000 rows, with an explicit truncation notice.
- The redesigned interface uses a compact ribbon, searchable sidebar, object tabs, striped datasheets and a SQL editor with line numbers. It retains the familiar Access workspace layout. Native Liquid Glass, exact Access workflow parity and complete accessibility support are not verified.

## Evidence and continuation

- `SPEC.md`: architecture, scope and milestone acceptance.
- `RESEARCH.md`: versioned sources, alternatives and limitations.
- `REA_FINDINGS.md`: observations from the supplied installer and extracted Access executable.
- `COMPATIBILITY.md`: feature discovery, implementation and verification.
- `DESIGN.md`: Apple HIG sources and the macOS/Access layout requirements. Supplied reference screenshots and design documents are retained locally and excluded from public Git history.
- Run `./scripts/test.sh` after fetching dependencies to reproduce portable checks. Optional native application-object checks require your own licensed reference files via `ACCESS_RUNTIME_REFERENCE_DIR`. Historical local verification reached 198 assertions; raw machine-specific logs are excluded from the public repository.
- `PROGRESS.md`: current release state and next work.
- `LEGAL.md` and `licenses/THIRD-PARTY.md`: provenance, redistribution boundaries and third-party notices.

No Microsoft executable is bundled with this independent application. The user-supplied installer is used only as a local reference artifact.
