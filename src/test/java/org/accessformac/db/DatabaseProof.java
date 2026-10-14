package org.accessformac.db;

import io.github.spannm.jackcess.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Integration checks against actual native .accdb files; no mocks or SQLite. */
public final class DatabaseProof {
    private static int assertions;
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        assertions++; System.out.println("PASS " + label);
    }
    private static byte[] hash(Path p) throws Exception { return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)); }
    private static Object scalar(AccessDatabase db, String sql) throws Exception {
        return db.execute(sql).rows().get(0).get(0);
    }
    private static void expectFailure(RunnableWithException run, String label) throws Exception {
        try { run.run(); } catch (Exception expected) { check(true,label + " — " + expected.getMessage().split("\n")[0]); return; }
        throw new AssertionError("Expected failure: " + label);
    }
    @FunctionalInterface interface RunnableWithException { void run() throws Exception; }
    private static void directOpenProof(Path dir) throws Exception {
        Path sourceDirectory = Files.createDirectory(dir.resolve("direct-source"));
        Path source = sourceDirectory.resolve("Music.accdb");
        AccessDatabase.createSample(source);
        byte[] initialHash = hash(source);
        Path backups = dir.resolve("direct-recovery");
        String previous = System.getProperty("access.backupDirectory");
        System.setProperty("access.backupDirectory", backups.toString());
        try {
            Path initialBackup;
            try (AccessDatabase db = AccessDatabase.open(source)) {
                check(db.isDirect() && db.originalPath().equals(source.toRealPath()) && db.workingPath().equals(source.toRealPath()),
                    "Normal open directly addresses the selected database");
                check(db.tables().equals(List.of("Customers", "Orders")), "Direct open reads native tables");
                initialBackup = db.recoveryBackupPath();
                check(initialBackup.startsWith(backups) && !initialBackup.startsWith(sourceDirectory)
                    && Arrays.equals(initialHash, hash(initialBackup)), "Direct open verifies initial recovery bytes outside the source folder");
            }
            check(Arrays.equals(initialHash, hash(source)), "Read-only activity in a direct session preserves source bytes");
            try (AccessDatabase db = AccessDatabase.open(source)) {
                check(db.recoveryBackupPath().equals(initialBackup), "Unchanged direct reopen reuses the existing recovery snapshot");
                check(db.updateRecord("Customers", Map.of("CustomerID", 1), "Notes", "Direct 'prepared'; 東京") == 1,
                    "Direct session supports prepared record editing");
            }
            try (Database nativeDb = new DatabaseBuilder(source).withReadOnly(true).open()) {
                check(nativeDb.getTable("Customers").iterator().next().get("Notes").equals("Direct 'prepared'; 東京"),
                    "Direct prepared edit persists in the selected native file");
            }
            check(Arrays.equals(initialHash, hash(initialBackup)), "Initial direct recovery snapshot remains unchanged after editing");
            Path changedBackup;
            try (AccessDatabase db = AccessDatabase.open(source)) {
                changedBackup = db.recoveryBackupPath();
                check(!changedBackup.equals(initialBackup) && Arrays.equals(hash(source), hash(changedBackup)),
                    "Changed file receives a new verified recovery version on next open");
                check(scalar(db, "SELECT Notes FROM Customers WHERE CustomerID=1").equals("Direct 'prepared'; 東京"),
                    "Direct edit survives normal reopen");
            }
            try (AccessDatabase db = AccessDatabase.open(source)) {
                check(db.recoveryBackupPath().equals(changedBackup), "Repeated unchanged direct open reuses the latest recovery version");
            }
            try (var files = Files.list(sourceDirectory)) {
                check(files.map(p -> p.getFileName().toString()).filter(name -> !name.equals("Music.laccdb")).toList().equals(List.of("Music.accdb")),
                    "Repeated direct open creates no working copies or backups beside the selected file");
            }
            try (var files = Files.walk(backups)) {
                check(files.filter(Files::isRegularFile).count() == 2, "Direct recovery history contains only the two distinct file versions");
            }
            byte[] editedHash = hash(source);
            Files.write(changedBackup, new byte[]{1, 2, 3});
            expectFailure(() -> AccessDatabase.open(source), "Corrupt existing recovery snapshot prevents direct opening");
            check(Arrays.equals(editedHash, hash(source)), "Rejected corrupt recovery leaves selected source bytes unchanged");
            System.setProperty("access.backupDirectory", sourceDirectory.toString());
            expectFailure(() -> AccessDatabase.open(source), "Direct recovery directory cannot be the selected file folder");
            check(Arrays.equals(editedHash, hash(source)), "Rejected recovery directory leaves selected source unchanged");
            Path nestedRecovery = sourceDirectory.resolve("Application Support/Recovery");
            System.setProperty("access.backupDirectory", nestedRecovery.toString());
            Path nestedSnapshot;
            try (AccessDatabase db = AccessDatabase.open(source)) {
                nestedSnapshot = db.recoveryBackupPath();
                check(nestedSnapshot.startsWith(nestedRecovery) && Arrays.equals(editedHash, hash(nestedSnapshot)),
                    "Separate recovery subfolder supports a database in an ancestor folder such as Home");
            }
            Files.delete(nestedSnapshot); Files.createLink(nestedSnapshot, source);
            expectFailure(() -> AccessDatabase.open(source), "A hard link to the selected database cannot count as a recovery backup");
            check(Arrays.equals(editedHash, hash(source)), "Rejected same-file recovery leaves selected database bytes unchanged");
            System.setProperty("access.backupDirectory", backups.toString());
            Path unsafe = sourceDirectory.resolve("unsafe;music.accdb"); Files.copy(source, unsafe);
            expectFailure(() -> AccessDatabase.open(unsafe), "Direct opening rejects JDBC delimiter paths");
            check(Arrays.equals(editedHash, hash(unsafe)), "Rejected direct path remains byte-identical");
            Path wrong = sourceDirectory.resolve("music.sqlite"); Files.copy(source, wrong);
            expectFailure(() -> AccessDatabase.open(wrong), "Direct opening rejects non-Access extensions");
            Path mdb = sourceDirectory.resolve("Music.mdb"); AccessDatabase.createBlank(mdb);
            try (AccessDatabase db = AccessDatabase.open(mdb)) {
                check(db.isDirect() && !db.isReadOnly(), "Normal open supports native editable MDB");
                db.execute("CREATE TABLE Tracks (ID COUNTER PRIMARY KEY, Title TEXT(80))");
                db.insertRecord("Tracks", Map.of("Title", "Direct MDB"));
            }
            try (AccessDatabase db = AccessDatabase.open(mdb)) {
                check(scalar(db, "SELECT Title FROM Tracks").equals("Direct MDB"), "Native MDB direct edits persist on reopening");
            }
        } finally {
            if (previous == null) System.clearProperty("access.backupDirectory");
            else System.setProperty("access.backupDirectory", previous);
        }
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path scratch = root.resolve("../../work/database-proof").normalize(); Files.createDirectories(scratch);
        Path dir = Files.createTempDirectory(scratch,"run-");
        directOpenProof(dir);
        Path blank = dir.resolve("new-project/Blank.accdb");
        AccessDatabase.createBlank(blank);
        byte[] blankHash = hash(blank);
        try (Database nativeDb = new DatabaseBuilder(blank).withReadOnly(true).open()) {
            check(nativeDb.getFileFormat() == Database.FileFormat.V2010,"Blank database is a genuine Access 2010 ACCDB");
            check(nativeDb.getTableNames().isEmpty() && nativeDb.getQueries().isEmpty(),"Blank database has no sample tables or queries");
        }
        Path blankWorking = blank.resolveSibling("Blank-working.accdb");
        try (AccessDatabase db = AccessDatabase.openCopy(blank,blankWorking)) {
            check(db.tables().isEmpty() && !db.isReadOnly(),"Blank ACCDB opens as an editable empty working copy");
            db.execute("CREATE TABLE Projects (ID COUNTER PRIMARY KEY, Name TEXT(80))");
            check(db.insertRecord("Projects",Map.of("Name","My first database")) == 1,"Blank database supports table design and prepared record insertion");
        }
        try (Database nativeDb = new DatabaseBuilder(blankWorking).withReadOnly(true).open()) {
            check(nativeDb.getTable("Projects").getRowCount() == 1,"New table and record persist in the native blank ACCDB working copy");
        }
        check(Arrays.equals(blankHash,hash(blank)) && Arrays.equals(blankHash,hash(AccessDatabase.backupPath(blankWorking))),"Blank original and recovery backup remain byte-identical after edits");
        expectFailure(()->AccessDatabase.createBlank(blank),"Blank creation never overwrites an existing database");
        check(Arrays.equals(blankHash,hash(blank)),"Rejected blank creation leaves existing database byte-identical");
        Path blankMdb = dir.resolve("Blank.mdb");
        AccessDatabase.createBlank(blankMdb);
        try (Database nativeDb = new DatabaseBuilder(blankMdb).withReadOnly(true).open()) {
            check(nativeDb.getFileFormat() == Database.FileFormat.V2003 && nativeDb.getTableNames().isEmpty(),"Blank MDB is genuine empty Jet4 Access 2003 format");
        }
        Path wrongBlank = dir.resolve("Blank.sqlite");
        expectFailure(()->AccessDatabase.createBlank(wrongBlank),"Blank creation rejects non-Access extensions");
        check(!Files.exists(wrongBlank),"Rejected blank extension leaves no file");
        Path unsafeBlank = dir.resolve("unsafe;blank.accdb");
        expectFailure(()->AccessDatabase.createBlank(unsafeBlank),"Blank creation rejects JDBC-delimiter paths before writing");
        check(!Files.exists(unsafeBlank),"Rejected blank path leaves no file");
        Path original = dir.resolve("original.accdb"), working = dir.resolve("practice.accdb");
        AccessDatabase.createSample(original); byte[] originalHash = hash(original);
        try (AccessDatabase db = AccessDatabase.openCopy(original,working)) {
            check(!db.isDirect() && db.recoveryBackupPath().equals(AccessDatabase.backupPath(working)), "Explicit copy mode retains its adjacent original backup contract");
            check(db.tables().equals(List.of("Customers","Orders")),"Native sample tables");
            check(db.columns("Customers").get(0).primaryKey(),"Native primary key metadata");
            check(db.columns("Customers").get(0).type().equals("AUTONUMBER"),"Native autonumber metadata");
            check(!db.isReadOnly(),"Basic ACCDB editable working copy");
            check(((Number) scalar(db,"SELECT Count(*) FROM Customers")).intValue()==3,"SELECT count");
            check(scalar(db,"SELECT FullName FROM Customers WHERE CustomerID=1").equals("Zoë Dlamini"),"Unicode name");
            check(scalar(db,"SELECT Notes FROM Customers WHERE CustomerID=2").equals("Unicode: 東京"),"Non-Latin Unicode text");
            check(scalar(db,"SELECT Notes FROM Customers WHERE CustomerID=1")==null,"SQL NULL stays null");
            check(scalar(db,"SELECT Nz(Notes, 'Missing') FROM Customers WHERE CustomerID=1").equals("Missing"),"Access Nz NULL expression");
            check(scalar(db,"SELECT IIf(City='Cape Town','Cape','Other') FROM Customers WHERE CustomerID=1").equals("Cape"),"Access IIf expression");
            check(scalar(db,"SELECT Left(FullName,3) & '!' FROM Customers WHERE CustomerID=1").equals("Zoë!"),"Access Left and & concatenation");
            check(((Number) scalar(db,"SELECT DateDiff('d',#2026-01-01#,#2026-01-05#) FROM Customers WHERE CustomerID=1")).intValue()==4,"Access date literal and DateDiff");
            check(((Number) scalar(db,"SELECT Count(*) FROM Customers WHERE JoinedOn >= #2026-01-01#")).intValue()==2,"Date field filtering");
            check(((Number) scalar(db,"SELECT Count(*) FROM Customers WHERE FullName LIKE 'Zo*'")).intValue()==1,"Access wildcard LIKE *");
            var grouped=db.execute("SELECT c.CustomerID, Sum(o.Amount) AS Total FROM Customers AS c INNER JOIN Orders AS o ON c.CustomerID=o.CustomerID GROUP BY c.CustomerID HAVING Sum(o.Amount)>100 ORDER BY c.CustomerID");
            check(grouped.rows().size()==2 && ((Number)grouped.rows().get(0).get(1)).doubleValue()==200,"JOIN/GROUP BY/HAVING/money aggregation");
            check(((Number)scalar(db,"SELECT Count(*) FROM Customers WHERE CustomerID IN (SELECT CustomerID FROM Orders)")).intValue()==2,"Subquery IN");
            check(db.execute("UPDATE Customers SET City='Durban' WHERE CustomerID=3").updateCount()==1,"Native UPDATE");
            check(db.execute("INSERT INTO Customers (FullName,City) VALUES ('Test Record','Pretoria')").updateCount()==1,"Native INSERT with autonumber");
            check(db.execute("DELETE FROM Customers WHERE FullName='Test Record'").updateCount()==1,"Native DELETE");
            expectFailure(()->db.execute("INSERT INTO Orders (CustomerID,Amount) VALUES (999,1)"),"Referential integrity rejects missing parent");
            db.execute("CREATE TABLE Practice (ID COUNTER PRIMARY KEY, Label TEXT(50))");
            db.execute("INSERT INTO Practice (Label) VALUES ('persisted')");
            db.execute("CREATE INDEX LabelIndex ON Practice (Label)");
            check(db.tables().contains("Practice"),"Native CREATE TABLE and index");
            db.execute("ALTER TABLE Practice ADD COLUMN OptionalText TEXT(20)");
            check(db.columns("Practice").stream().anyMatch(c->c.name().equals("OptionalText")),"Native ALTER TABLE ADD COLUMN");
            db.execute("CREATE TABLE [UI Basic Types] ([ID] COUNTER NOT NULL, [Name] TEXT(255), PRIMARY KEY ([ID]))");
            db.execute("ALTER TABLE [UI Basic Types] ADD COLUMN [Added Field] TEXT(255)");
            check(db.columns("UI Basic Types").size()==3&&db.columns("UI Basic Types").get(0).primaryKey(),"UI table designer COUNTER NOT NULL/table primary key and ADD COLUMN syntax");
            expectFailure(()->db.execute("ALTER TABLE [UI Basic Types] DROP COLUMN [Added Field]"),"DROP COLUMN unsupported engine error remains explicit");
            check(db.columns("UI Basic Types").size()==3,"Failed unsupported DROP COLUMN leaves native schema unchanged");
            db.execute("CREATE TABLE Rules (ID LONG PRIMARY KEY, RequiredName TEXT(40) NOT NULL, Score LONG DEFAULT 7, Enabled YESNO DEFAULT TRUE)");
            db.execute("INSERT INTO Rules (ID,RequiredName) VALUES (1,'Defaults')");
            check(((Number)scalar(db,"SELECT Score FROM Rules")).intValue()==7&&scalar(db,"SELECT Enabled FROM Rules").equals(true),"Native numeric/boolean defaults apply to SQL inserts");
            expectFailure(()->db.execute("INSERT INTO Rules (ID) VALUES (2)"),"Required native field rejects missing value");
            db.execute("CREATE TABLE Parents (ID LONG PRIMARY KEY)"); db.execute("CREATE TABLE Children (ID LONG PRIMARY KEY, ParentID LONG)");
            db.execute("ALTER TABLE Children ADD CONSTRAINT ParentsChildren FOREIGN KEY (ParentID) REFERENCES Parents (ID)");
            expectFailure(()->db.execute("INSERT INTO Children (ID,ParentID) VALUES (1,99)"),"ALTER TABLE foreign key enforces parent integrity");
            check(db.relationships().stream().anyMatch(r->r.fromTable().equals("Parents")&&r.toTable().equals("Children")&&r.referentialIntegrity()),"SQL-created foreign key appears in native relationship metadata");
            db.execute("CREATE TABLE Disposable (ID LONG)");
            db.execute("DROP TABLE Disposable");
            check(!db.tables().contains("Disposable"),"Native DROP TABLE");
            db.execute("SELECT CustomerID, FullName INTO CustomerNames FROM Customers");
            check(db.tables().contains("CustomerNames") && ((Number)scalar(db,"SELECT Count(*) FROM CustomerNames")).intValue()==3,"SELECT INTO translates to native make-table with data");
            check(AccessDatabase.parameters("PARAMETERS [Chosen City] Text; SELECT * FROM Customers WHERE City=[Chosen City]").get(0).name().equals("Chosen City"),"Named parameter introspection");
            check(db.execute("PARAMETERS [Chosen City] Text; SELECT * FROM Customers WHERE City=[Chosen City]",List.of("Cape Town")).rows().size()==1,"Named Access PARAMETERS binding");
            check(db.execute("PARAMETERS wanted Long; SELECT FullName FROM Customers WHERE CustomerID=wanted OR CustomerID=wanted",List.of("1")).rows().size()==1,"Repeated named parameter binds once with typed coercion");
            check(db.execute("SELECT * FROM Customers WHERE CustomerID=?",List.of(1)).rows().size()==1,"Positional PreparedStatement binding");
            check(db.execute("SELECT * FROM Customers WHERE City=?",List.of("Cape Town' OR 1=1 --")).rows().isEmpty(),"Parameter values cannot inject SQL");
            expectFailure(()->db.execute("PARAMETERS p Long; SELECT FullName FROM Customers WHERE CustomerID=p",List.of(2147483648L)),"Typed Long parameter rejects overflow rather than wrapping");
            expectFailure(()->db.execute("PARAMETERS p Long; SELECT FullName FROM Customers WHERE CustomerID=p",List.of(new java.math.BigDecimal("1.5"))),"Typed integral parameter rejects fractional truncation");
            check(db.execute("PARAMETERS p DateTime; SELECT * FROM Customers WHERE JoinedOn>=p",List.of("2026-01-01")).rows().size()==2,"Typed DateTime parameter binds ISO date value");
            check(db.execute("PARAMETERS p Currency; SELECT * FROM Orders WHERE Amount>p",List.of("100.25")).rows().size()==2,"Typed Currency parameter binds decimal value");
            check(db.execute("PARAMETERS p Text; SELECT * FROM Customers WHERE p IS NULL",Arrays.asList((Object)null)).rows().size()==3,"Typed parameter accepts explicit SQL NULL");
            check(db.fields("Customers").stream().filter(f->f.name().equals("CustomerID")).allMatch(f->f.autoNumber()&&!f.writable()),"Datasheet autonumber metadata prevents editing");
            check(db.relationships().stream().anyMatch(r->r.name().equals("CustomersOrders")&&r.referentialIntegrity()),"Relationship viewer metadata");
            check(db.updateRecord("Customers",Map.of("CustomerID",1),"Notes","Prepared 'note'; 東京")==1,"Prepared primary-key update");
            check(db.insertRecord("Customers",Map.of("FullName","Record 'safe'; Ω","City","Test"))==1,"Prepared scalar record insert");
            int id=((Number)scalar(db,"SELECT CustomerID FROM Customers WHERE City='Test'")).intValue();
            check(db.deleteRecord("Customers",Map.of("CustomerID",id))==1,"Prepared primary-key delete");
            expectFailure(()->db.updateRecord("Customers",Map.of(),"City","x"),"Prepared edits require complete primary key");
            expectFailure(()->db.updateRecord("Customers",Map.of("CustomerID",1),"CustomerID",100),"Prepared edits reject primary-key changes");
            expectFailure(()->db.saveQuery("CustomerSummary", "SELECT City, Count(*) AS CustomerCount FROM Customers GROUP BY City"),"Native saved SELECT creation explicitly unavailable");
            check(db.savedQueries().isEmpty(),"Unsupported save leaves native catalog unchanged");
            check(scalar(db,"SELECT [FullName] FROM [Customers] WHERE [CustomerID]=1").equals("Zoë Dlamini"),"SQL gate accepts bracket identifiers");
            check(scalar(db,"SELECT 'literal; IN java -- unchanged' FROM Customers WHERE CustomerID=1").equals("literal; IN java -- unchanged"),"SQL gate accepts keyword/comment-like quoted literals");
            expectFailure(()->db.execute("SELECT * FROM Customers IN '/tmp/external.accdb'"),"External IN database blocked");
            expectFailure(()->db.execute("CREATE FUNCTION Hack() RETURNS VARCHAR LANGUAGE JAVA EXTERNAL NAME 'CLASSPATH:x'"),"Java routine creation blocked");
            expectFailure(()->db.execute("UPDATE Customers SET City='x'; DELETE FROM Customers"),"Multiple statements blocked");
            expectFailure(()->db.execute("PARAMETERS p TEXT; SELECT * FROM Customers WHERE City=p"),"Parameter binding explicitly unavailable");
            expectFailure(()->db.execute("DELETE FROM [MSysObjects]"),"Bracket-quoted system catalog mutation blocked");
            db.flush();
            check(Arrays.equals(originalHash,hash(original)),"Original unchanged after mutations (SHA256)");
            check(Arrays.equals(originalHash,hash(AccessDatabase.backupPath(working))),"Recovery backup byte-identical to original");
        }
        try (Database nativeDb=new DatabaseBuilder(working).withReadOnly(true).open()) {
            check(nativeDb.getTable("Customers").getRowCount()==3,"Jackcess independently reopens persisted records");
            check(nativeDb.getRelationships().stream().anyMatch(r->r.getName().equals("CustomersOrders")),"Native relationship persists");
            check(nativeDb.getTable("Practice").getIndexes().stream().anyMatch(i->i.getName().equals("LabelIndex")),"Native secondary index persists");
            check(nativeDb.getQueries().isEmpty(),"Native query catalog unchanged on unsupported creation");
            check(nativeDb.getTable("CustomerNames").getRowCount()==3,"Make-table records persist in native ACCDB after close");
            check(nativeDb.getTable("Rules").getColumn("Score").getProperties().getValue(PropertyMap.DEFAULT_VALUE_PROP)!=null,"Default expression persists as native Access field property");
            check(nativeDb.getRelationships().stream().anyMatch(r->r.getFromTable().getName().equals("Parents")&&r.getToTable().getName().equals("Children")),"SQL-created relationship persists in native file");
        }
        try (AccessDatabase reopen=AccessDatabase.openCopy(working,dir.resolve("reopened.accdb"))) {
            check(scalar(reopen,"SELECT City FROM Customers WHERE CustomerID=3").equals("Durban"),"UPDATE survives JDBC close/reopen");
            check(scalar(reopen,"SELECT Label FROM Practice").equals("persisted"),"DDL/data survives JDBC close/reopen");
            check(scalar(reopen,"SELECT Notes FROM Customers WHERE CustomerID=1").equals("Prepared 'note'; 東京"),"Prepared record update persists on reopen");

        }
        expectFailure(()->AccessDatabase.openCopy(original,working),"Existing working copy never overwritten");
        expectFailure(()->AccessDatabase.createSample(original),"Sample creation never overwrites an existing file");
        check(Arrays.equals(originalHash,hash(original)),"Original unchanged after reopen (SHA256)");
        Path conflictCopy=dir.resolve("backup-conflict.accdb");
        Files.writeString(AccessDatabase.backupPath(conflictCopy),"existing backup");
        expectFailure(()->AccessDatabase.openCopy(original,conflictCopy),"Existing backup never overwritten");
        check(Files.readString(AccessDatabase.backupPath(conflictCopy)).equals("existing backup") && !Files.exists(conflictCopy),"Backup collision leaves both paths unchanged");
        Path linked=dir.resolve("linked.accdb"); AccessDatabase.createSample(linked);
        try (Database nativeDb=new DatabaseBuilder(linked).open()) { nativeDb.createLinkedTable("ExternalCustomers",original.toString(),"Customers"); }
        expectFailure(()->AccessDatabase.openCopy(linked,dir.resolve("linked-copy.accdb")),"Linked table refused before external resolution");
        check(!Files.exists(dir.resolve("linked-copy.accdb")),"Rejected linked database leaves no working file");
        byte[] linkedHash = hash(linked);
        expectFailure(() -> AccessDatabase.open(linked), "Direct linked database refused before JDBC resolves external data");
        check(Arrays.equals(linkedHash, hash(linked)), "Rejected direct linked file stays byte-identical");
        // A separate large fixture validates explicit row-cap signalling.
        Path large=dir.resolve("large.accdb"); AccessDatabase.createSample(large);
        try (Database nativeDb=new DatabaseBuilder(large).open()) {
            Table t=new TableBuilder("Numbers").addColumn(new ColumnBuilder("N",DataType.LONG)).toTable(nativeDb);
            for(int n=0;n<5002;n++) t.addRow(n);
        }
        try (AccessDatabase db=AccessDatabase.openCopy(large,dir.resolve("large-copy.accdb"))) {
            var result=db.execute("SELECT N FROM Numbers ORDER BY N");
            check(result.rows().size()==5000 && result.truncated(),"Result cap 5000 explicitly reports truncation");
            expectFailure(()->db.saveQueryExperimental("UnverifiedLarge","SELECT N FROM Numbers ORDER BY N"),"Experimental native save refuses truncated verification");
            check(db.savedQueries().isEmpty(),"Truncated native save leaves catalog unchanged");
        }
        Path existingQueryFixture=root.resolve("fixtures/queryV2010.accdb");
        byte[] existingQueryHash=hash(existingQueryFixture);
        try(AccessDatabase db=AccessDatabase.openCopy(existingQueryFixture,dir.resolve("native-queries.accdb"))) {
            check(db.savedQueries().size()==9,"Nine existing native query definitions enumerate");
            check(db.savedQueries().stream().anyMatch(q->q.name().equals("SelectQuery") && q.sql().startsWith("SELECT DISTINCT")),"Existing native SELECT SQL renders faithfully");
            check(db.savedQueries().stream().anyMatch(q->q.name().equals("PassthroughQuery") && q.sql().startsWith("-- Unsupported pass-through")),"Pass-through query explicitly flagged unavailable");
            var select=db.savedQueries().stream().filter(q->q.name().equals("SelectQuery")).findFirst().orElseThrow();
            check(db.execute(select.sql()).rows().isEmpty(),"Existing native saved SELECT executes from its SQL definition");
            db.execute("CREATE TABLE AddedTable (ID LONG)");
        }
        try(Database nativeDb=new DatabaseBuilder(dir.resolve("native-queries.accdb")).withReadOnly(true).open()) {
            check(nativeDb.getQueries().size()==9,"All nine native query objects survive unrelated DDL roundtrip");
        }
        check(Arrays.equals(existingQueryHash,hash(existingQueryFixture)),"Upstream native-query fixture unchanged SHA256");
        Path complexFixture=root.resolve("fixtures/complexDataV2010.accdb");
        byte[] complexHash=hash(complexFixture);
        try(AccessDatabase db=AccessDatabase.openCopy(complexFixture,dir.resolve("complex-copy.accdb"))) {
            check(db.isReadOnly() && db.limitations().size()>=3,"Attachment/multivalue/history fields detected and opened read only");
            expectFailure(()->db.execute("DELETE FROM Table1"),"Advanced-field copy refuses mutations");
            check(!db.execute("SELECT * FROM Table1").rows().isEmpty(),"Advanced-field fixture supports safe read access");
        }
        check(Arrays.equals(complexHash,hash(complexFixture)),"Advanced-field fixture unchanged SHA256");
        Path mdbOriginal=dir.resolve("original.mdb"); AccessDatabase.createSample(mdbOriginal);byte[] mdbHash=hash(mdbOriginal);
        try(Database nativeDb=new DatabaseBuilder(mdbOriginal).withReadOnly(true).open()){check(nativeDb.getFileFormat()==Database.FileFormat.V2003,"Genuine Jet4 Access2003 MDB created (not renamed ACCDB)");}
        Path mdbWorking=dir.resolve("working.mdb");
        try(AccessDatabase db=AccessDatabase.openCopy(mdbOriginal,mdbWorking)) {
            check(db.execute("SELECT * FROM Customers").rows().size()==3,"Real MDB queries on macOS");
            check(db.updateRecord("Customers",Map.of("CustomerID",1),"City","MdbCity")==1,"Real MDB prepared edit");
        }
        try(AccessDatabase db=AccessDatabase.openCopy(mdbWorking,dir.resolve("reopened.mdb"))) {
            check(scalar(db,"SELECT City FROM Customers WHERE CustomerID=1").equals("MdbCity"),"Real MDB edit persists after native/JDBC reopen");
        }
        check(Arrays.equals(mdbHash,hash(mdbOriginal)),"MDB original unchanged SHA256");
        expectFailure(()->AccessDatabase.openCopy(mdbOriginal,dir.resolve("disguised.accdb")),"Working copy preserves actual MDB extension");
        Path nativeSavedCopy=dir.resolve("native-saved-copy.accdb");
        try(AccessDatabase db=AccessDatabase.openCopy(original,nativeSavedCopy)) {
            expectFailure(()->db.saveQueryExperimental("Customers","SELECT * FROM Customers"),"Native query name cannot collide with table");
            var directSummary=db.execute("SELECT City, Count(*) AS CustomerCount FROM Customers GROUP BY City ORDER BY City");
            db.saveQueryExperimental("CitySummary","SELECT City, Count(*) AS CustomerCount FROM Customers GROUP BY City ORDER BY City");
            check(db.savedQueries().stream().anyMatch(q->q.name().equals("CitySummary")),"Experimental native SELECT saved in catalog");
            var savedSummary=db.execute("SELECT * FROM CitySummary");
            check(savedSummary.rows().size()==2,"Experimental native SELECT executes by saved name after mirror reload");
            System.out.println("Metadata comparison direct="+directSummary.columns()+" saved="+savedSummary.columns()+"; count JDBC classes direct="+directSummary.rows().get(0).get(1).getClass().getSimpleName()+" saved="+savedSummary.rows().get(0).get(1).getClass().getSimpleName());
            db.saveQueryExperimental("NamedPeople","SELECT DISTINCT c.FullName AS [Person Name], 'literal; token' AS Note FROM Customers AS c WHERE c.CustomerID < 3 ORDER BY c.FullName DESC");
            check(db.execute("SELECT * FROM NamedPeople").rows().size()==2,"Native SELECT aliases/literals/DISTINCT/WHERE/DESC roundtrip");
            expectFailure(()->db.saveQueryExperimental("UnavailableJoin","SELECT c.FullName FROM Customers c INNER JOIN Orders o ON c.CustomerID=o.CustomerID"),"Unsupported native save grammar explicitly rejected");
            check(db.savedQueries().size()==2,"Unsupported native save leaves catalog unchanged");
            expectFailure(()->db.saveQueryExperimental("CitySummary","SELECT * FROM Customers"),"Experimental native query refuses overwrite");
            try(var stream=Files.list(dir)){check(stream.filter(f->f.getFileName().toString().startsWith("native-saved-copy.accdb.pre-query-")).count()==2,"Every experimental native save retains pre-save ACCDB backup");}
            check(Arrays.equals(originalHash,hash(original)),"Experimental native query save never changes original");
        }
        try(Database nativeDb=new DatabaseBuilder(nativeSavedCopy).withReadOnly(true).open()) {
            check(nativeDb.getQueries().size()==2&&nativeDb.getQueries().stream().allMatch(q->q.getType()==io.github.spannm.jackcess.query.Query.Type.SELECT),"Experimental saved queries independently reopen as native SELECT objects");
        }
        try(AccessDatabase db=AccessDatabase.openCopy(nativeSavedCopy,dir.resolve("native-saved-reopen.accdb"))) {
            check(db.execute("SELECT * FROM CitySummary").rows().size()==2,"Experimental saved query survives new file session");
        }
        Path failureCopy=dir.resolve("native-failure-copy.accdb");
        try(AccessDatabase db=AccessDatabase.openCopy(original,failureCopy)) {
            byte[] beforeFailure=hash(failureCopy);
            expectFailure(()->db.saveQueryExperimental("CopyFailure","SELECT * FROM Customers",(source,target)->{Files.write(target,new byte[]{1,2});throw new java.io.IOException("Injected partial-copy/disk failure");},NativeSelectQuery::write),"Partial-backup failure is reported without restoring partial bytes");
            check(Arrays.equals(beforeFailure,hash(failureCopy))&&db.savedQueries().isEmpty(),"Partial-backup failure leaves working file byte-identical and reconnects");
            check(db.execute("SELECT * FROM Customers").rows().size()==3,"Database remains usable after backup failure");
            expectFailure(()->db.saveQueryExperimental("WriteFailure","SELECT * FROM Customers",(source,target)->Files.copy(source,target),(nativeDb,name,sql)->{NativeSelectQuery.write(nativeDb,name,sql);throw new java.io.IOException("Injected post-mutation failure");}),"Post-mutation catalog failure triggers verified restore");
            check(Arrays.equals(beforeFailure,hash(failureCopy))&&db.savedQueries().isEmpty(),"Native catalog failure rolls back exact pre-save ACCDB bytes");
            check(db.execute("SELECT * FROM Customers").rows().size()==3,"Database remains usable after catalog rollback");
        }
        Path preservedQueries=dir.resolve("preserved-native-queries.accdb");
        try(AccessDatabase db=AccessDatabase.openCopy(existingQueryFixture,preservedQueries)) {
            db.saveQueryExperimental("AddedNativeSelect","SELECT col1 FROM Table1");
            check(db.savedQueries().size()==10,"Experimental native save preserves nine unrelated native query objects");
        }
        Path opaque=dir.resolve("opaque-table.accdb"); AccessDatabase.createSample(opaque);
        try(Database db=new DatabaseBuilder(opaque).open()) {
            Table catalog=db.getSystemTable("MSysObjects");
            for(Row row:catalog)if("Customers".equals(row.get("Name"))){row.put("LvExtra",new byte[]{42});catalog.updateRow(row);break;}
        }
        try(AccessDatabase db=AccessDatabase.openCopy(opaque,dir.resolve("opaque-copy.accdb"))) {
            check(db.isReadOnly()&&db.limitations().stream().anyMatch(x->x.contains("opaque Access")),"Synthetic opaque table payload forces read only (data-macro candidate guard)");
            expectFailure(()->db.insertRecord("Customers",Map.of("FullName","bypassed")),"Opaque metadata refuses prepared mutation");
        }
        Path appObject=dir.resolve("application-object.accdb"); AccessDatabase.createSample(appObject);
        try(Database db=new DatabaseBuilder(appObject).open()) {
            Table catalog=db.getSystemTable("MSysObjects"); Object formsId=null;
            for(Row row:catalog)if("Forms".equals(row.get("Name"))){formsId=row.get("Id");break;}
            Map<String,Object> object=new HashMap<>();object.put("Id",19000000);object.put("ParentId",formsId);object.put("Name","SyntheticUnsupportedForm");object.put("Type",(short)777);
            catalog.addRowFromMap(object);
        }
        try(AccessDatabase db=AccessDatabase.openCopy(appObject,dir.resolve("application-object-copy.accdb"))) {
            check(db.isReadOnly()&&db.limitations().stream().anyMatch(x->x.contains("SyntheticUnsupportedForm")),"Application container child forces read only without relying on guessed Type numbers");
            expectFailure(()->db.execute("UPDATE Customers SET City='bypassed'"),"Unsupported application object refuses action SQL");
        }
        byte[] applicationHash = hash(appObject);
        String priorBackupDirectory = System.getProperty("access.backupDirectory");
        System.setProperty("access.backupDirectory", dir.resolveSibling(dir.getFileName() + "-readonly-recovery").toString());
        try (AccessDatabase db = AccessDatabase.open(appObject)) {
            check(db.isDirect() && db.isReadOnly(), "Direct open retains read-only protection for unsupported application objects");
            expectFailure(() -> db.updateRecord("Customers", Map.of("CustomerID", 1), "City", "bypassed"),
                "Direct unsupported-object file rejects prepared editing");
            check(Arrays.equals(applicationHash, hash(db.recoveryBackupPath())), "Direct read-only file has a verified original recovery backup");
        } finally {
            if (priorBackupDirectory == null) System.clearProperty("access.backupDirectory");
            else System.setProperty("access.backupDirectory", priorBackupDirectory);
        }
        check(Arrays.equals(applicationHash, hash(appObject)), "Direct read-only session leaves unsupported native objects byte-identical");
        Path encryptedFixture=root.resolve("fixtures/db2007-enc.accdb");
        byte[] encryptedHash=hash(encryptedFixture);
        expectFailure(()->AccessDatabase.openCopy(encryptedFixture,dir.resolve("encrypted-copy.accdb")),"Encrypted ACCDB fixture explicitly rejected");
        check(!Files.exists(dir.resolve("encrypted-copy.accdb")) && Arrays.equals(encryptedHash,hash(encryptedFixture)),"Encrypted rejection leaves source unchanged and creates no copy");
        expectFailure(() -> AccessDatabase.open(encryptedFixture), "Direct encrypted database explicitly rejected before JDBC");
        check(Arrays.equals(encryptedHash,hash(encryptedFixture)), "Rejected direct encrypted source remains byte-identical");
        if(args.length>0 && args[0].equals("--publish-fixtures")) {
            Path fixture=root.resolve("fixtures/Practice.accdb");
            if(!Files.exists(fixture)) Files.copy(working,fixture);
            Path clean=root.resolve("fixtures/OriginalSample.accdb");
            if(!Files.exists(clean)) Files.copy(original,clean);
            Path nativeExample=root.resolve("fixtures/NativeQueriesExperimental.accdb");
            if(!Files.exists(nativeExample))Files.copy(nativeSavedCopy,nativeExample);
            Path mdbExample=root.resolve("fixtures/OriginalSample.mdb");
            if(!Files.exists(mdbExample))Files.copy(mdbOriginal,mdbExample);
        }
        System.out.println("RESULT: "+assertions+" integration assertions passed on real ACCDB files.");
        System.out.println("Java: "+System.getProperty("java.version")+", architecture: "+System.getProperty("os.arch"));
        System.out.println("UCanAccess5.1.8 / Jackcess5.1.7 / HSQLDB2.7.4; Windows Access reopen comparison NOT performed.");
        System.out.println("Disposable evidence retained: "+dir);
    }
}
