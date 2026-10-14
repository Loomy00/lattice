package org.accessformac.install;

import io.github.spannm.jackcess.ColumnBuilder;
import io.github.spannm.jackcess.DataType;
import io.github.spannm.jackcess.Database;
import io.github.spannm.jackcess.DatabaseBuilder;
import io.github.spannm.jackcess.PropertyMap;
import io.github.spannm.jackcess.Table;
import io.github.spannm.jackcess.TableBuilder;
import org.accessformac.db.AccessDatabase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Comparator;

/** Installation proof that also runs inside jpackage's reduced Java runtime. */
public final class RuntimeVerification {
    private RuntimeVerification() {}

    public static void verify() throws Exception {
        // Jackcess loads these classes only when real Access expressions are encountered.
        // A plain sample database does not cover this runtime dependency.
        Class.forName("javax.script.Bindings");
        Class.forName("javax.script.ScriptEngineManager");
        Path scratch = Files.createTempDirectory("lattice-runtime-proof-");
        String previousBackupDirectory = System.getProperty("access.backupDirectory");
        System.setProperty("access.backupDirectory", scratch.resolve("recovery").toString());
        try {
            Path databases = Files.createDirectory(scratch.resolve("databases"));
            Path original = databases.resolve("expressions.mdb");
            try (Database db = DatabaseBuilder.create(Database.FileFormat.V2000, original.toFile())) {
                Table table = new TableBuilder("RuntimeRules")
                    .addColumn(new ColumnBuilder("ID", DataType.LONG))
                    .addColumn(new ColumnBuilder("Score", DataType.LONG)
                        .withProperty(PropertyMap.DEFAULT_VALUE_PROP, "=2+3")
                        .withProperty(PropertyMap.VALIDATION_RULE_PROP, ">=0 And <=10"))
                    .withPrimaryKey("ID").toTable(db);
                table.addRow(1, 5);
            }
            // Reopen persisted metadata before JDBC so losing the fixture expressions is a failure.
            try (Database db = new DatabaseBuilder(original).withReadOnly(true).open()) {
                var properties = db.getTable("RuntimeRules").getColumn("Score").getProperties();
                require("=2+3".equals(properties.getValue(PropertyMap.DEFAULT_VALUE_PROP)), "MDB default expression metadata");
                require(">=0 And <=10".equals(properties.getValue(PropertyMap.VALIDATION_RULE_PROP)), "MDB validation rule metadata");
            }
            Path working = databases.resolve("working.mdb");
            try (AccessDatabase db = AccessDatabase.openCopy(original, working)) {
                require(db.execute("SELECT Score FROM RuntimeRules WHERE ID=1").rows().size() == 1, "MDB read");
                require(db.execute("INSERT INTO RuntimeRules (ID) VALUES (2)").updateCount() == 1, "MDB insert with default expression");
                require(score(db, 2) == 5, "MDB default expression evaluation");
                require(db.execute("UPDATE RuntimeRules SET Score=7 WHERE ID=1").updateCount() == 1, "MDB update");
                db.flush();
            }
            try (AccessDatabase db = AccessDatabase.open(working)) {
                require(score(db, 1) == 7 && score(db, 2) == 5, "MDB expressions and edits survive close/reopen");
            }
            // Exercise Jackcess's native validator separately: a rejected UCanAccess flush
            // can close its backing channel, which would obscure the runtime dependency check.
            try (Database db = new DatabaseBuilder(working).open()) {
                boolean rejected = false;
                try { db.getTable("RuntimeRules").addRow(3, 99); }
                catch (IOException expected) { rejected = true; }
                require(rejected, "MDB validation rule rejects invalid data");
            }
            System.out.println("Lattice bundled-runtime MDB expression round-trip verification passed.");
        } finally {
            if (previousBackupDirectory == null) System.clearProperty("access.backupDirectory");
            else System.setProperty("access.backupDirectory", previousBackupDirectory);
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static int score(AccessDatabase db, int id) throws Exception {
        return ((Number) db.execute("SELECT Score FROM RuntimeRules WHERE ID=" + id).rows().get(0).get(0)).intValue();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Installation verification failed: " + message);
    }

    public static void main(String[] args) throws Exception { verify(); }
}
