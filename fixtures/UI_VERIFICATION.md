# Packaged UI verification

Date: 2026-10-12. Host: Apple Silicon macOS, bundled Java 17 application.

The lead launched the actual packaged `Access for Mac.app` and inspected its accessibility tree and screenshot using Computer Use.

Verified through the application interface:

1. File picker opened `OriginalSample.accdb` and prompted for a distinct working-copy path.
2. The native database opened successfully; Customers and Orders appeared in navigation.
3. Running `SELECT * FROM [Customers]` displayed three rows, including Unicode names, Japanese text, date values and a NULL cell.
4. File → Open SQL File loaded `UPDATE Customers SET City='Durban' WHERE CustomerID=3;` into a query tab.
5. Run reported one affected record saved to the working copy.
6. Closing the window completed without a database-save error.
7. A separate Java/Jackcess process then reopened the actual file and asserted that CustomerID 3 had City `Durban`. The original retained `Cape Town` and remained SHA-256 identical to the original-backup file.

The screenshot showed a complete workspace without overlapping controls at the tested 1240×820 logical window size. This is a layout check, not an Access 2016 pixel-match check.

Automation limitations: Computer Use could operate native dialogs, menu items and command buttons, but Java text-area typing and outline selection were not verified through its accessibility actions. SQL-file loading supplied the query for the write test. Manual keyboard editing and table double-click interaction are not marked passed by this run.

These checks covered the initial packaged SQL workspace. Later added record-editing, design and experimental query-writing features require their own checks and are not implied by this result. Windows Access itself was not run.

Scratch evidence: `../../work/UI-Smoke.accdb`, the unchanged original backup, `UI-Update.sql`, and `UiPersistenceProof.java` (paths relative to the project root).
