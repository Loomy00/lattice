# Access-for-Mac project

Build an independent macOS Access-compatible desktop app from the user's ACCESS_FOR_MAC.md brief. Preserve the broad parity goal, but never claim unverified support.

## Team and cost
User explicitly requests subagents for most work. Preferred implementation/research agents: GPT-6.1 Sol at High effort. Narrow verifiable tasks may use GPT-6 Luna at Low/Medium. Maximum three subagents; no recursive delegation. Lead integrates and verifies. Keep concise evidence and avoid duplicate research.

## Ownership and integration
- Research agent: RESEARCH.md, COMPATIBILITY.md only.
- Database agent: src/main/java/org/accessformac/db/, src/test/, scripts/build.sh, scripts/test.sh, scripts/fetch-deps.sh, dependency lock and licenses, fixtures/ only.
- UI agent: src/main/java/org/accessformac/ui/, scripts/package-macos.sh only.
- Lead: SPEC.md, PROGRESS.md, README.md, integration fixes after communicating ownership.

Java 17 is installed on Apple Silicon. Prefer JDBC with UCanAccess/Jackcess, pending compatibility proof. No ongoing AI dependency. No SQLite substitution. Keep downloads reproducible and version-pinned. The user's 2026-10-14 instruction supersedes the former copy-only opening rule: normal Open edits the selected database, with a verified automatic recovery backup stored by the app. Offer Open a Copy explicitly; do not force new duplicate filenames on each open. Diagnostic and regression work on user-supplied files still uses scratch copies and verifies the supplied original is unchanged. Unsupported application objects must be preserved and honestly described.

## Engine/UI contract
Provide org.accessformac.db.AccessDatabase implementing AutoCloseable:
- static AccessDatabase openCopy(java.nio.file.Path original, java.nio.file.Path workingCopy) throws Exception
- static void createSample(java.nio.file.Path target) throws Exception
- java.nio.file.Path originalPath(); java.nio.file.Path workingPath();
- java.util.List<String> tables() throws Exception
- java.util.List<ColumnInfo> columns(String table) throws Exception
- QueryResult execute(String sql) throws Exception
- java.util.List<SavedQuery> savedQueries() throws Exception
- void saveQuery(String name, String sql) throws Exception
- void flush() throws Exception
- void close() throws Exception
Nested public records ColumnInfo(String name,String type,boolean nullable,boolean primaryKey), QueryResult(java.util.List<String> columns,java.util.List<java.util.List<Object>> rows,int updateCount,boolean truncated), SavedQuery(String name,String sql).
Read query rows should be capped sensibly with explicit truncated flag. Ensure copying all files is safe and refuse overwriting existing working copies. Native saved-query persistence must be proven or explicitly unsupported; UI must surface limitations. Do not fake successful operations.

## Checks
Use real disposable .accdb fixtures, close and reopen for persistence, record Access-specific SQL outputs. Windows Access comparisons and Microsoft application-object preservation require actual evidence; do not mark passed without it. Keep source/bundled outputs in this directory; scratch/cache under ../../work. The user authorized public GitHub publication on 2026-10-13: publish reviewed project source and original assets only, obey `.gitignore`, and keep personal databases, proprietary reference files, supplied screenshots/design documents and machine-specific logs local. Binary redistribution requires its own complete third-party license/source compliance.


## Current product/design direction (2026-10-12)

The product name is **Lattice**. Deliver a polished Apple-esque Mac interface and an original distinctive lattice logo while retaining Access's top command categories, left object navigation, central object tabs and datasheets/SQL workspace. Local supplied design guidance and screenshots are excluded from public history. Use `DESIGN.md` and official Apple HIG references; local references may be consulted when available. Adapt its principles to Swing/Aqua without expensive simulated glass, unsupported native Liquid Glass claims, or a major shell rewrite for appearance alone. Preserve all verified database protections and explicit native-object execution limits.
