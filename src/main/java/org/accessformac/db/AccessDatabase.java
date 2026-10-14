package org.accessformac.db;

import io.github.spannm.jackcess.*;
import net.ucanaccess.jdbc.UcanaccessConnection;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Native Access operations with verified recovery backups before opening for edits. */
public final class AccessDatabase implements AutoCloseable {
    public record ColumnInfo(String name, String type, boolean nullable, boolean primaryKey) {}
    public record QueryResult(List<String> columns, List<List<Object>> rows, int updateCount, boolean truncated) {}
    public record SavedQuery(String name, String sql) {}
    public record ParameterInfo(String name, String type) {}
    public record FieldInfo(String name, String type, boolean nullable, boolean primaryKey, boolean autoNumber, boolean writable) {}
    public record RelationshipInfo(String name, String fromTable, String toTable, List<String> fromColumns, List<String> toColumns,
                                   boolean referentialIntegrity, boolean cascadeUpdates, boolean cascadeDeletes) {}
    public static final int ROW_LIMIT = 5000;
    private final Path original, working, recoveryBackup;
    private final boolean direct;
    private UcanaccessConnection connection;
    private final boolean readOnly;
    private final List<String> limitations;
    private boolean closed;

    private AccessDatabase(Path original, Path working, Path recoveryBackup, boolean direct,
                           UcanaccessConnection connection, boolean readOnly, List<String> limitations) {
        this.original = original; this.working = working; this.connection = connection;
        this.recoveryBackup = recoveryBackup; this.direct = direct;
        this.readOnly = readOnly; this.limitations = List.copyOf(limitations);
    }

    /** Open and edit the selected file. Reopening unchanged bytes reuses its recovery snapshot. */
    public static AccessDatabase open(Path original) throws Exception {
        Path source = sourcePath(original);
        if (source.toString().contains(";")) throw new IOException("A database path containing ';' cannot be safely passed to JDBC.");
        byte[] sourceHash = digest(source);
        Capabilities capabilities = inspectCapabilities(source);
        if (!Arrays.equals(sourceHash, digest(source)))
            throw new IOException("Database changed during compatibility inspection; close other editors and retry.");
        Path backup = recoverySnapshot(source, sourceHash);
        if (!Arrays.equals(sourceHash, digest(source)))
            throw new IOException("Database changed while preparing recovery; close other editors and retry.");
        UcanaccessConnection conn = connect(source, capabilities.readOnly());
        return new AccessDatabase(source, source, backup, true, conn, capabilities.readOnly(), capabilities.limitations());
    }

    private static Path sourcePath(Path original) throws IOException {
        Path source = original.toRealPath();
        String extension = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!extension.endsWith(".accdb") && !extension.endsWith(".mdb"))
            throw new IOException("Choose an actual .accdb or .mdb file.");
        if (!Files.isRegularFile(source)) throw new IOException("Choose a regular Access database file.");
        return source;
    }

    private static Path recoverySnapshot(Path source, byte[] sourceHash) throws Exception {
        String configured = System.getProperty("access.backupDirectory");
        Path root = configured == null || configured.isBlank()
            ? Path.of(System.getProperty("user.home"), "Library", "Application Support", "Lattice", "Recovery Backups")
            : Path.of(configured).toAbsolutePath().normalize();
        // Use a separate folder, including when the source is in Home and AppSupport is below it.
        if (root.toAbsolutePath().normalize().equals(source.getParent()))
            throw new IOException("Choose a recovery backup directory outside the database folder.");
        Files.createDirectories(root);
        root = root.toRealPath();
        if (root.equals(source.getParent()))
            throw new IOException("Choose a recovery backup directory outside the database folder.");
        String pathKey = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(source.toString().getBytes(StandardCharsets.UTF_8)));
        Path directory = root.resolve(pathKey);
        Files.createDirectories(directory);
        directory = directory.toRealPath();
        if (directory.equals(source.getParent()))
            throw new IOException("Choose a recovery backup directory outside the database folder.");
        String extension = source.toString().toLowerCase(Locale.ROOT).endsWith(".mdb") ? ".mdb" : ".accdb";
        Path backup = directory.resolve(HexFormat.of().formatHex(sourceHash) + extension);
        if (backup.equals(source) || (Files.exists(backup, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(backup, source)))
            throw new IOException("Recovery backup must be a separate file from the selected database.");
        if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
            Path temporary = Files.createTempFile(directory, ".recovery-", ".tmp");
            try {
                Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
                if (!Arrays.equals(sourceHash, digest(temporary)) || !Arrays.equals(sourceHash, digest(source)))
                    throw new IOException("Source changed while backing up; close other editors and retry.");
                try { Files.move(temporary, backup); }
                catch (FileAlreadyExistsException concurrentSnapshot) { /* Verify the winning snapshot below. */ }
            } finally { Files.deleteIfExists(temporary); }
        }
        if (!Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS) || !Arrays.equals(sourceHash, digest(backup)))
            throw new IOException("Recovery backup verification failed; the selected database was not opened: " + backup);
        return backup;
    }

    private static UcanaccessConnection connect(Path path, boolean readOnly) throws Exception {
        Class.forName("net.ucanaccess.jdbc.UcanaccessDriver");
        UcanaccessConnection conn = null;
        try {
            conn = (UcanaccessConnection) DriverManager.getConnection("jdbc:ucanaccess://" + path
                + ";memory=true;immediatelyReleaseResources=true;openExclusive=true");
            conn.setReadOnly(readOnly);
            return conn;
        } catch (Exception | LinkageError failed) {
            if (conn != null) try { conn.close(); } catch (Exception closeFailed) { failed.addSuppressed(closeFailed); }
            throw failed;
        }
    }

    public static AccessDatabase openCopy(Path original, Path workingCopy) throws Exception {
        Path source = sourcePath(original);
        Path copy = workingCopy.toAbsolutePath().normalize();
        String extension = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!extension.endsWith(".accdb") && !extension.endsWith(".mdb"))
            throw new IOException("Choose an actual .accdb or .mdb file.");
        if (!Files.isRegularFile(source) || source.equals(copy))
            throw new IOException("The working copy must be a different regular file.");
        String expectedExtension=extension.endsWith(".mdb")?".mdb":".accdb";
        if(!copy.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(expectedExtension))
            throw new IOException("The working filename must retain the original " + expectedExtension + " format extension.");
        if (copy.toString().contains(";")) throw new IOException("A working path containing ';' cannot be safely passed to JDBC.");
        Path backup = backupPath(copy);
        if (Files.exists(copy, LinkOption.NOFOLLOW_LINKS) || Files.exists(backup, LinkOption.NOFOLLOW_LINKS))
            throw new FileAlreadyExistsException("Choose a new working filename; existing copy or backup will never be replaced: " + copy);
        byte[] sourceHash = digest(source);
        Files.createDirectories(copy.getParent());
        boolean createdCopy = false, createdBackup = false; UcanaccessConnection conn = null;
        try {
            try(var out=Files.newOutputStream(copy,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {createdCopy=true;Files.copy(source,out);}
            try(var out=Files.newOutputStream(backup,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {createdBackup=true;Files.copy(source,out);}
            if (!Arrays.equals(sourceHash, digest(source)) || !Arrays.equals(sourceHash, digest(copy))
                || !Arrays.equals(sourceHash, digest(backup))) throw new IOException("Source changed while copying; close Access and retry.");
            Capabilities capabilities=inspectCapabilities(copy);
            if(!Arrays.equals(sourceHash,digest(copy)) || !Arrays.equals(sourceHash,digest(source)))
                throw new IOException("Database changed during compatibility inspection; close other editors and retry.");
            conn = connect(copy, capabilities.readOnly());
            return new AccessDatabase(source, copy, backup, false, conn, capabilities.readOnly(), capabilities.limitations());
        } catch (Exception | LinkageError failed) {
            if(conn!=null)try{conn.close();}catch(Exception closeFailed){failed.addSuppressed(closeFailed);}
            if(createdCopy)try{Files.deleteIfExists(copy);}catch(Exception cleanupFailed){failed.addSuppressed(cleanupFailed);}
            if(createdBackup)try{Files.deleteIfExists(backup);}catch(Exception cleanupFailed){failed.addSuppressed(cleanupFailed);}
            throw failed;
        }
    }

    private record Capabilities(boolean readOnly,List<String> limitations) {}
    private static Capabilities inspectCapabilities(Path path) throws Exception {
        List<String> limitations = new ArrayList<>();
        boolean readOnly = false;
        // Preflight before JDBC can resolve linked tables. No external database is ever opened.
        try (Database inspect = new DatabaseBuilder(path).withReadOnly(true).open()) {
            Table catalog = inspect.getSystemTable("MSysObjects");
            List<Row> catalogRows = new ArrayList<>(); catalog.forEach(catalogRows::add);
            Set<Object> applicationContainers = new HashSet<>();
            for (Row row : catalogRows) {
                if (Set.of("Forms", "Reports", "Modules", "Scripts", "DataAccessPages").contains(row.get("Name")))
                    applicationContainers.add(row.get("Id"));
            }
            for (Row row : catalogRows) {
                if (applicationContainers.contains(row.get("ParentId"))) {
                    readOnly = true;
                    limitations.add("Read only: unsupported application object '" + row.get("Name") + "'. Its native bytes are preserved; design and execution are unavailable.");
                }
                // LvExtra/LvModule are opaque catalog payloads. A non-null table payload may
                // encode data macros or other unsupported behavior; never silently bypass it.
                if (row.get("Type") instanceof Number type && type.intValue() == 1
                    && inspect.getTableNames().contains(row.get("Name"))
                    && (row.get("LvExtra") != null || row.get("LvModule") != null)) {
                    readOnly = true;
                    limitations.add("Read only: table '" + row.get("Name") + "' has opaque Access metadata/data-macro candidate payloads. Execution is unverified.");
                }
            }
            inspect.setLinkResolver((db, link) -> { throw new IOException("Linked databases are not supported: " + link); });
            if (inspect.getDatabasePassword() != null && !inspect.getDatabasePassword().isEmpty())
                throw new UnsupportedOperationException("Password protected/encrypted databases are not supported in this build.");
            if (inspect.getFileFormat() == Database.FileFormat.V1997) { readOnly = true; limitations.add("Access 97 is read only."); }
            for (TableMetaData metadata : inspect.newTableMetaDataIterable()) {
                if (metadata.isLinked()) throw new UnsupportedOperationException("Linked table '" + metadata.getName()
                    + "' requires external data. This build refuses linked databases before resolving them.");
                if (!metadata.isSystem()) {
                    for (Column col : metadata.open(inspect).getColumns()) {
                        if (col.getComplexInfo() != null || col.isCalculated() || col.isAppendOnly() || col.getType() == DataType.OLE) {
                            readOnly = true;
                            limitations.add("Read only: advanced field " + metadata.getName() + "." + col.getName() + " (" + col.getType() + ").");
                        }
                    }
                }
            }
            for (var query : inspect.getQueries()) {
                if (query.getType() == io.github.spannm.jackcess.query.Query.Type.PASSTHROUGH)
                    limitations.add("Pass-through query '" + query.getName() + "' is preserved but cannot execute external server SQL.");
                // UCanAccess loads saved SELECTs into its internal mirror at connection time.
                // Refuse external database clauses before that loading can touch another file.
                String definition = query.toSQLString();
                if (query.getType() == io.github.spannm.jackcess.query.Query.Type.SELECT) {
                    try { validateSql(SqlSupport.plan(definition).sql()); }
                    catch (SQLException unsafe) {
                        throw new UnsupportedOperationException("Saved query '" + query.getName() + "' uses unsupported or external SQL. The database is refused before loading its queries: " + unsafe.getMessage(), unsafe);
                    }
                }
            }
        } catch (io.github.spannm.jackcess.impl.UnsupportedCodecException encrypted) {
            throw new UnsupportedOperationException("Encrypted databases are not supported; decrypt a copy in Windows Access first.", encrypted);
        }
        return new Capabilities(readOnly,List.copyOf(limitations));
    }

    public static Path backupPath(Path workingCopy) {
        return workingCopy.resolveSibling(workingCopy.getFileName() + ".original-backup");
    }
    public Path originalPath() { return original; }
    public Path workingPath() { return working; }
    public boolean isDirect() { return direct; }
    public Path recoveryBackupPath() { return recoveryBackup; }
    public boolean isReadOnly() { return readOnly; }
    public List<String> limitations() { return limitations; }
    private void requireOpen() throws SQLException { if (closed) throw new SQLException("Database is closed."); }

    public synchronized List<String> tables() throws Exception {
        requireOpen(); List<String> names = new ArrayList<>(connection.getDbIO().getTableNames());
        names.sort(String.CASE_INSENSITIVE_ORDER); return List.copyOf(names);
    }
    public synchronized List<ColumnInfo> columns(String table) throws Exception {
        requireOpen(); Table nativeTable = connection.getDbIO().getTable(table);
        if (nativeTable == null) throw new SQLException("Table not found: " + table);
        Set<String> keys = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Index idx : nativeTable.getIndexes()) if (idx.isPrimaryKey())
            for (Index.Column key : idx.getColumns()) keys.add(key.getName());
        List<ColumnInfo> result = new ArrayList<>();
        for (Column col : nativeTable.getColumns()) {
            boolean primaryKey = keys.contains(col.getName());
            boolean required = Boolean.TRUE.equals(col.getProperties().getValue(PropertyMap.REQUIRED_PROP));
            String type = col.isAutoNumber() ? "AUTONUMBER" : col.getType().name();
            result.add(new ColumnInfo(col.getName(), type, !required && !primaryKey, primaryKey));
        }
        return List.copyOf(result);
    }

    public static List<ParameterInfo> parameters(String sql) throws Exception { return SqlSupport.plan(sql).parameters(); }
    public synchronized QueryResult execute(String sql) throws Exception { return execute(sql, List.of()); }
    public synchronized QueryResult execute(String sql, List<Object> values) throws Exception {
        requireOpen(); SqlSupport.Plan plan = SqlSupport.plan(sql);
        if (values == null || values.size() != plan.parameters().size())
            throw new SQLException("Expected " + plan.parameters().size() + " parameter values; received " + (values == null ? 0 : values.size()) + ".");
        List<String> tokens = validateSql(plan.sql());
        boolean selectOnly = tokens.get(0).equals("SELECT") && !tokens.contains("INTO");
        if (readOnly && !selectOnly) throw new SQLException("This file is open read only because it contains unsupported Access objects, behavior or fields.");
        String executable = SqlSupport.makeTable(plan.sql());
        validateSql(executable);
        if (!executable.equals(plan.sql()) && !plan.bindings().isEmpty())
            throw new SQLException("Parameterized make-table queries are unavailable; create a table and INSERT INTO ... SELECT with bound parameters.");
        try (Statement statement = plan.bindings().isEmpty() ? connection.createStatement() : connection.prepareStatement(executable)) {
            statement.setQueryTimeout(30); statement.setMaxRows(ROW_LIMIT + 1);
            if (statement instanceof PreparedStatement prepared) {
                for (int i = 0; i < plan.bindings().size(); i++) {
                    int parameter = plan.bindings().get(i); int type = SqlSupport.sqlType(plan.parameters().get(parameter).type());
                    Object value = SqlSupport.coerce(values.get(parameter), type);
                    if (value == null) prepared.setNull(i + 1, type == Types.JAVA_OBJECT ? Types.VARCHAR : type);
                    else if (type == Types.JAVA_OBJECT) prepared.setObject(i + 1, value);
                    else prepared.setObject(i + 1, value, type);
                }
            }
            boolean hasResults = statement instanceof PreparedStatement prepared ? prepared.execute() : statement.execute(executable);
            if (!hasResults) return new QueryResult(List.of(), List.of(), statement.getUpdateCount(), false);
            try (ResultSet rs = statement.getResultSet()) {
                int count = rs.getMetaData().getColumnCount(); List<String> names = new ArrayList<>();
                for (int i = 1; i <= count; i++) names.add(rs.getMetaData().getColumnLabel(i));
                List<List<Object>> rows = new ArrayList<>(); boolean truncated = false;
                while (rs.next()) {
                    if (rows.size() == ROW_LIMIT) { truncated = true; break; }
                    List<Object> row = new ArrayList<>();
                    for (int i = 1; i <= count; i++) row.add(rs.getObject(i));
                    rows.add(Collections.unmodifiableList(row));
                }
                return new QueryResult(List.copyOf(names), List.copyOf(rows), -1, truncated);
            }
        }
    }
    public synchronized List<FieldInfo> fields(String table) throws Exception {
        requireOpen(); Table nativeTable = table(table); List<FieldInfo> result = new ArrayList<>();
        for (ColumnInfo info : columns(table)) {
            Column col = nativeTable.getColumn(info.name());
            boolean safe = !readOnly && !info.primaryKey() && !col.isAutoNumber() && scalarField(col);
            result.add(new FieldInfo(info.name(), info.type(), info.nullable(), info.primaryKey(), col.isAutoNumber(), safe));
        }
        return List.copyOf(result);
    }
    public synchronized List<RelationshipInfo> relationships() throws Exception {
        requireOpen(); List<RelationshipInfo> result = new ArrayList<>();
        for (Relationship rel : connection.getDbIO().getRelationships())
            result.add(new RelationshipInfo(rel.getName(), rel.getFromTable().getName(), rel.getToTable().getName(),
                rel.getFromColumns().stream().map(Column::getName).toList(), rel.getToColumns().stream().map(Column::getName).toList(),
                rel.hasReferentialIntegrity(), rel.cascadeUpdates(), rel.cascadeDeletes()));
        return List.copyOf(result);
    }
    private Table table(String name) throws Exception {
        if (name == null || name.toUpperCase(Locale.ROOT).startsWith("MSYS")) throw new SQLException("Select a user table.");
        Table result = connection.getDbIO().getTable(name);
        if (result == null || result.isSystem()) throw new SQLException("User table not found: " + name);
        return result;
    }
    private static boolean scalarField(Column col) {
        return col.getComplexInfo() == null && !col.isCalculated() && !col.isAppendOnly()
            && col.getType() != DataType.OLE && col.getType() != DataType.BINARY;
    }
    private static String identifier(String name) { return "[" + name.replace("]", "]]") + "]"; }
    private void requireWritable() throws Exception {
        requireOpen(); if (readOnly) throw new SQLException("This database is open read only; unsupported Access behavior must not be bypassed.");
    }
    private Map<String, Object> keys(Table table, Map<String, Object> supplied) throws Exception {
        List<String> primary = columns(table.getName()).stream().filter(ColumnInfo::primaryKey).map(ColumnInfo::name).toList();
        if (primary.isEmpty() || supplied == null || supplied.size() != primary.size())
            throw new SQLException("Record edits require the complete primary key.");
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : primary) {
            var entry = supplied.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).findFirst();
            if (entry.isEmpty() || entry.get().getValue() == null) throw new SQLException("Missing primary key: " + name);
            result.put(name, SqlSupport.coerce(entry.get().getValue(), table.getColumn(name).getSQLType()));
        }
        return result;
    }
    public synchronized int updateRecord(String tableName, Map<String, Object> primaryKeyValues, String column, Object newValue) throws Exception {
        requireWritable(); Table table = table(tableName); Map<String,Object> keys = keys(table, primaryKeyValues);
        Column field = table.getColumn(column);
        if (field == null || !scalarField(field) || field.isAutoNumber() || keys.containsKey(field.getName()))
            throw new SQLException("This field cannot be edited safely in Datasheet View.");
        List<Object> values = new ArrayList<>(); values.add(SqlSupport.coerce(newValue,field.getSQLType())); values.addAll(keys.values());
        String where = String.join(" AND ", keys.keySet().stream().map(k -> identifier(k) + "=?").toList());
        return execute("UPDATE " + identifier(table.getName()) + " SET " + identifier(field.getName()) + "=? WHERE " + where,values).updateCount();
    }
    public synchronized int deleteRecord(String tableName, Map<String,Object> primaryKeyValues) throws Exception {
        requireWritable(); Table table = table(tableName); Map<String,Object> keys = keys(table, primaryKeyValues);
        String where = String.join(" AND ",keys.keySet().stream().map(k -> identifier(k) + "=?").toList());
        return execute("DELETE FROM " + identifier(table.getName()) + " WHERE " + where,new ArrayList<>(keys.values())).updateCount();
    }
    public synchronized int insertRecord(String tableName, Map<String,Object> supplied) throws Exception {
        requireWritable(); Table table = table(tableName);
        if (supplied == null || supplied.isEmpty()) throw new SQLException("Provide at least one scalar field value.");
        List<String> names = new ArrayList<>(); List<Object> values = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (var entry : supplied.entrySet()) {
            Column field = table.getColumn(entry.getKey());
            if (field == null || !scalarField(field) || field.isAutoNumber()) throw new SQLException("Field cannot be inserted: " + entry.getKey());
            if (!seen.add(field.getName().toUpperCase(Locale.ROOT))) throw new SQLException("Duplicate field: " + field.getName());
            names.add(identifier(field.getName())); values.add(SqlSupport.coerce(entry.getValue(),field.getSQLType()));
        }
        return execute("INSERT INTO " + identifier(table.getName()) + " (" + String.join(",",names) + ") VALUES ("
            + String.join(",",Collections.nCopies(names.size(),"?")) + ")",values).updateCount();
    }

    public synchronized List<SavedQuery> savedQueries() throws Exception {
        requireOpen(); List<SavedQuery> result = new ArrayList<>();
        for (var q : connection.getDbIO().getQueries()) {
            if (!q.isHidden()) {
                try { result.add(new SavedQuery(q.getName(),
                    q.getType() == io.github.spannm.jackcess.query.Query.Type.PASSTHROUGH
                        ? "-- Unsupported pass-through query; retained unchanged in native file.\n" + q.toSQLString()
                        : q.toSQLString().replace("\u0000", ""))); }
                catch (UnsupportedOperationException unsupported) {
                    throw new UnsupportedOperationException("Cannot render native query '" + q.getName() + "' (" + q.getType() + "). Its definition is retained unchanged in the file.", unsupported);
                }
            }
        }
        result.sort(Comparator.comparing(SavedQuery::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }
    public synchronized void saveQuery(String name, String sql) throws Exception {
        requireOpen();
        if (name == null || !name.matches("[\\p{L}_][\\p{L}\\p{N}_ ]{0,63}"))
            throw new SQLException("Use a query name of 1–64 letters, numbers, spaces or underscores, starting with a letter.");
        List<String> tokens = validateSql(sql);
        if (!tokens.get(0).equals("SELECT") || tokens.contains("INTO"))
            throw new UnsupportedOperationException("Native saved queries currently support SELECT only, without PARAMETERS or INTO.");
        for (SavedQuery q : savedQueries()) if (q.name().equalsIgnoreCase(name))
            throw new SQLException("A saved query with this name already exists. Choose a new name.");
        if (!Boolean.getBoolean("access.experimentalNativeQueries"))
            throw new UnsupportedOperationException("Native query saving requires the explicitly experimental catalog writer; UCanAccess CREATE VIEW is unsupported. Use Save Native Query (Experimental) or save a separate .sql draft. Windows Access verification is pending.");
        saveQueryExperimental(name, sql);
    }
    @FunctionalInterface interface BackupCopier { void copy(Path source, Path target) throws Exception; }
    @FunctionalInterface interface CatalogWriter { String write(Database db, String name, String sql) throws Exception; }
    public synchronized void saveQueryExperimental(String name, String sql) throws Exception {
        saveQueryExperimental(name,sql,(source,target)->Files.copy(source,target),NativeSelectQuery::write);
    }
    // Internal I/O seam exercises interrupted-copy and post-mutation failures on disposable files.
    synchronized void saveQueryExperimental(String name, String sql, BackupCopier copier, CatalogWriter writer) throws Exception {
        requireWritable();
        if (name == null || !name.matches("[\\p{L}_][\\p{L}\\p{N}_ ]{0,63}")) throw new SQLException("Choose a new query name of 1–64 letters, numbers, spaces or underscores.");
        if (!SqlSupport.plan(sql).parameters().isEmpty()) throw new SQLException("Experimental native saving does not yet support parameters.");
        List<String> tokens=validateSql(sql);
        if (!tokens.get(0).equals("SELECT")||tokens.contains("INTO")) throw new SQLException("Experimental native save supports SELECT only.");
        for (SavedQuery query:savedQueries()) if(query.name().equalsIgnoreCase(name)) throw new SQLException("A native query already has this name; choose a new name.");
        for (String tableName:tables()) if(tableName.equalsIgnoreCase(name)) throw new SQLException("A table already has this name; choose a distinct query name.");
        NativeSelectQuery.Definition definition=NativeSelectQuery.parse(name,sql,-1000000000);
        QueryResult preview=execute(sql), renderedPreview=execute(definition.rendered());
        if(preview.truncated()||renderedPreview.truncated())throw new SQLException("Experimental native saving requires a fully verified result set of at most " + ROW_LIMIT + " rows. The query may be kept as a .sql draft.");
        if (!equivalentResults(preview,renderedPreview))
            throw new SQLException("Native query reconstruction differs from the entered SQL; no file has been changed.");
        List<SavedQuery> before=savedQueries();
        Path backup=working.resolveSibling(working.getFileName()+".pre-query-"+UUID.randomUUID()+(working.toString().toLowerCase(Locale.ROOT).endsWith(".mdb")?".mdb":".accdb"));
        boolean verifiedBackup=false, mutationStarted=false, restored=false;
        try {
            flush(); connection.close(); closed=true;
            copier.copy(working,backup);
            if(!Arrays.equals(digest(working),digest(backup)))throw new IOException("Pre-query backup verification failed.");
            verifiedBackup=true;
            String nativeSql;
            mutationStarted=true;
            try(Database nativeDb=new DatabaseBuilder(working).open()){nativeSql=writer.write(nativeDb,name,sql);}
            try(Database verify=new DatabaseBuilder(working).withReadOnly(true).open()) {
                var queries=verify.getQueries();
                if(queries.size()!=before.size()+1)throw new IOException("Native query count failed after reopening.");
                for(SavedQuery prior:before) {
                    var kept=queries.stream().filter(q->q.getName().equals(prior.name())).findFirst().orElseThrow();
                    String expected=prior.sql().replace("-- Unsupported pass-through query; retained unchanged in native file.\n","").replace("\u0000","");
                    if(!kept.toSQLString().replace("\u0000","").equals(expected))throw new IOException("An unrelated native query changed: "+prior.name());
                }
                var saved=queries.stream().filter(q->q.getName().equals(name)).findFirst().orElseThrow();
                if(saved.getType()!=io.github.spannm.jackcess.query.Query.Type.SELECT||!saved.toSQLString().equals(nativeSql))throw new IOException("Native SELECT definition failed after reopening.");
            }
            reconnect();
            QueryResult persisted=execute("SELECT * FROM ["+name+"]");
            if(!equivalentResults(persisted,preview))throw new IOException("Saved native query returned different results after JDBC reopening: columns " + persisted.columns() + " versus " + preview.columns() + "; row counts " + persisted.rows().size() + "/" + preview.rows().size());
        } catch(Exception failed) {
            if(!closed){try{connection.close();}catch(Exception closeFailure){failed.addSuppressed(closeFailure);}finally{closed=true;}}
            if(verifiedBackup && mutationStarted) {
                try {
                    Files.copy(backup,working,StandardCopyOption.REPLACE_EXISTING);
                    if(!Arrays.equals(digest(backup),digest(working)))throw new IOException("Restored-file hash mismatch.");
                    restored=true;
                }catch(Exception restoreFailed){failed.addSuppressed(restoreFailed);}
            }
            if(!mutationStarted || restored) {
                try{reconnect();}catch(Exception recoveryFailed){failed.addSuppressed(recoveryFailed);}
            }
            String state=!mutationStarted ? "The working file was not changed."
                : restored ? "The previous working file was restored and SHA256 verified."
                : "Automatic restore failed; recover the working file from the verified backup before continuing.";
            throw new IOException("Experimental native save failed. " + state + " Backup path: " + backup,failed);
        }
    }
    private static boolean equivalentResults(QueryResult first, QueryResult second) {
        if(first.columns().size()!=second.columns().size()||first.rows().size()!=second.rows().size()||first.truncated()!=second.truncated())return false;
        for(int i=0;i<first.columns().size();i++)if(!first.columns().get(i).equalsIgnoreCase(second.columns().get(i)))return false;
        for(int r=0;r<first.rows().size();r++)for(int c=0;c<first.columns().size();c++) {
            Object a=first.rows().get(r).get(c),b=second.rows().get(r).get(c);
            if(a instanceof Number && b instanceof Number){if(new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString()))!=0)return false;}
            else if(!Objects.deepEquals(a,b))return false;
        }
        return true;
    }
    private void reconnect() throws Exception {
        connection=connect(working,readOnly);closed=false;
    }
    public synchronized void flush() throws Exception { requireOpen(); connection.getDbIO().flush(); }
    public synchronized void close() throws Exception {
        if (!closed) {
            try { flush(); } finally { closed = true; connection.close(); }
        }
    }

    /** Conservative statement gate: deny engine commands, external IN files, Java routines, and multiple statements. */
    static List<String> validateSql(String sql) throws SQLException {
        if (sql == null || sql.isBlank()) throw new SQLException("Enter an Access SQL statement.");
        List<String> tokens = new ArrayList<>(); boolean ended = false;
        for (int i = 0; i < sql.length();) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (ended) throw new SQLException("Run one statement at a time.");
            if (c == ';') { ended = true; i++; continue; }
            if ((c == '-' && i+1 < sql.length() && sql.charAt(i+1) == '-')
                || (c == '/' && i+1 < sql.length() && sql.charAt(i+1) == '*'))
                throw new SQLException("SQL comments are not supported in executable statements.");
            if (c == '\'' || c == '"' || c == '[' || c == '#') {
                char terminator = c == '[' ? ']' : c; int start = i++; boolean found = false;
                while (i < sql.length()) {
                    if (sql.charAt(i++) == terminator) {
                        if (i < sql.length() && sql.charAt(i) == terminator && terminator != '#') { i++; continue; }
                        found = true; break;
                    }
                }
                if (!found) throw new SQLException("Unclosed string, identifier, or date literal at position " + start);
                tokens.add(c == '[' ? "IDENTIFIER:" + sql.substring(start+1,i-1).replace("]]", "]").toUpperCase(Locale.ROOT) : "LITERAL"); continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i++;
                while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) i++;
                tokens.add(sql.substring(start, i).toUpperCase(Locale.ROOT)); continue;
            }
            tokens.add(Character.toString(c)); i++;
        }
        if (tokens.isEmpty()) throw new SQLException("Enter an SQL statement.");
        String first = tokens.get(0);
        if (!Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "CREATE", "ALTER", "DROP").contains(first))
            throw new SQLException("Supported SQL: SELECT, INSERT, UPDATE, DELETE and table/index/view DDL.");
        if (Set.of("CREATE", "DROP").contains(first)
            && (tokens.size()<2 || !Set.of("TABLE", "INDEX", "VIEW", "UNIQUE").contains(tokens.get(1))))
            throw new SQLException("Only Access table, index and view DDL is enabled.");
        if (first.equals("ALTER") && (tokens.size()<2 || !tokens.get(1).equals("TABLE")))
            throw new SQLException("Only ALTER TABLE is enabled.");
        Set<String> denied = Set.of("JAVA", "EXTERNALLINK", "LINK", "LOAD_FILE", "READ_FILE", "WRITE_FILE", "FILE", "CSVREAD", "CSVWRITE", "TEXTTABLE", "SCRIPT", "BACKUP", "SHUTDOWN", "SYSTEM_LOBS", "INFORMATION_SCHEMA", "MSYSOBJECTS", "MSYSQUERIES", "MSYSRELATIONSHIPS");
        for (int i=0; i<tokens.size(); i++) {
            String token = tokens.get(i);
            if (denied.contains(token) || token.startsWith("MSYS") || token.startsWith("IDENTIFIER:MSYS")
                || token.equals("IDENTIFIER:INFORMATION_SCHEMA"))
                throw new SQLException("External files and internal engine commands are disabled.");
            if (tokens.get(i).equals("IN") && i+1<tokens.size() && tokens.get(i+1).equals("LITERAL"))
                throw new SQLException("External database IN clauses are disabled.");
        }
        return List.copyOf(tokens);
    }
    private static byte[] digest(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(path)) { byte[] buf = new byte[65536]; int n; while ((n=in.read(buf))>0) digest.update(buf,0,n); }
        return digest.digest();
    }

    /** Creates an empty native Access database, ready for table design or SQL DDL. */
    public static void createBlank(Path target) throws Exception {
        Path path = target.toAbsolutePath().normalize();
        String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".accdb") && !filename.endsWith(".mdb"))
            throw new IOException("New databases need an .accdb or .mdb extension.");
        if (path.toString().contains(";"))
            throw new IOException("A database path containing ';' cannot be safely passed to JDBC.");
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(path.toString());
        Database.FileFormat format = filename.endsWith(".mdb") ? Database.FileFormat.V2003 : Database.FileFormat.V2010;
        Files.createDirectories(path.getParent());
        // CREATE_NEW reserves the selected name and refuses existing files or symbolic links.
        Files.createFile(path);
        boolean completed = false;
        try {
            try (Database db = new DatabaseBuilder(path).withFileFormat(format).create()) {
                db.flush();
            }
            completed = true;
        } finally { if (!completed) Files.deleteIfExists(path); }
    }

    public static void createSample(Path target) throws Exception {
        Path path = target.toAbsolutePath().normalize();
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(path.toString());
        String filename=path.getFileName().toString().toLowerCase(Locale.ROOT);
        if(!filename.endsWith(".accdb")&&!filename.endsWith(".mdb"))throw new IOException("Sample databases need an .accdb or .mdb extension.");
        Database.FileFormat sampleFormat=filename.endsWith(".mdb")?Database.FileFormat.V2003:Database.FileFormat.V2010;
        Files.createDirectories(path.getParent());
        // Reserve the destination atomically so Jackcess can never overwrite another file.
        Files.createFile(path);
        boolean completed = false;
        try (Database db = new DatabaseBuilder(path).withFileFormat(sampleFormat).create()) {
            Table customers = new TableBuilder("Customers")
                .addColumn(new ColumnBuilder("CustomerID", DataType.LONG).withAutoNumber(true))
                .addColumn(new ColumnBuilder("FullName", DataType.TEXT).withLengthInUnits(100))
                .addColumn(new ColumnBuilder("City", DataType.TEXT).withLengthInUnits(80))
                .addColumn(new ColumnBuilder("JoinedOn", DataType.SHORT_DATE_TIME))
                .addColumn(new ColumnBuilder("Notes", DataType.MEMO))
                .withPrimaryKey("CustomerID").toTable(db);
            Table orders = new TableBuilder("Orders")
                .addColumn(new ColumnBuilder("OrderID", DataType.LONG).withAutoNumber(true))
                .addColumn(new ColumnBuilder("CustomerID", DataType.LONG))
                .addColumn(new ColumnBuilder("Amount", DataType.MONEY))
                .addColumn(new ColumnBuilder("OrderedOn", DataType.SHORT_DATE_TIME))
                .withPrimaryKey("OrderID").toTable(db);
            customers.addRow(Column.AUTO_NUMBER,"Zoë Dlamini","Cape Town", java.time.LocalDateTime.of(2026,1,5,0,0),null);
            customers.addRow(Column.AUTO_NUMBER,"Léa Müller","Johannesburg", java.time.LocalDateTime.of(2026,2,14,0,0),"Unicode: 東京");
            customers.addRow(Column.AUTO_NUMBER,"Amina Khan","Cape Town", java.time.LocalDateTime.of(2025,12,20,0,0),"Returning customer");
            orders.addRow(Column.AUTO_NUMBER,1,new java.math.BigDecimal("125.50"), java.time.LocalDateTime.of(2026,3,1,0,0));
            orders.addRow(Column.AUTO_NUMBER,1,new java.math.BigDecimal("74.50"), java.time.LocalDateTime.of(2026,3,2,0,0));
            orders.addRow(Column.AUTO_NUMBER,2,new java.math.BigDecimal("300.00"), java.time.LocalDateTime.of(2026,3,3,0,0));
            new RelationshipBuilder("Customers","Orders").addColumns("CustomerID","CustomerID")
                .withReferentialIntegrity().withName("CustomersOrders").toRelationship(db);
            db.flush(); completed = true;
        } finally { if (!completed) Files.deleteIfExists(path); }
    }
}
