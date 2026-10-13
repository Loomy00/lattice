package org.accessformac.ui;

import org.accessformac.db.AccessDatabase;
import org.accessformac.db.AccessDatabase.ColumnInfo;
import org.accessformac.objects.AccessObjectCatalog;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.Timestamp;
import java.util.*;

/** Exercises the actual designer generators and typed editor values against disposable native ACCDB. */
public final class UiFeatureProof {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        Path scratch = Path.of("../../work/ui-proof").toAbsolutePath().normalize();
        Files.createDirectories(scratch);
        Path directory = Files.createTempDirectory(scratch, "design-");
        Path source = directory.resolve("source.accdb"), working = directory.resolve("working.accdb");
        AccessDatabase.createSample(source);
        byte[] original = Files.readAllBytes(source);
        try (AccessDatabase db = AccessDatabase.openCopy(source, working)) {
            String create = TableDesignDialog.buildSql("UI Basic Types", List.of(
                new TableDesignDialog.FieldDefinition("ID", "COUNTER", true, true),
                new TableDesignDialog.FieldDefinition("Name", "TEXT(255)", false, false),
                new TableDesignDialog.FieldDefinition("Count", "LONG", false, false),
                new TableDesignDialog.FieldDefinition("Ratio", "DOUBLE", false, false),
                new TableDesignDialog.FieldDefinition("Cost", "CURRENCY", false, false),
                new TableDesignDialog.FieldDefinition("Created", "DATETIME", false, false),
                new TableDesignDialog.FieldDefinition("Enabled", "YESNO", false, false)));
            db.execute(create);
            check(db.fields("UI Basic Types").stream().anyMatch(f -> f.primaryKey() && f.autoNumber()), "Designer creates native AutoNumber primary key");
            rejects(() -> TableDesignDialog.buildSql("Duplicate", List.of(
                new TableDesignDialog.FieldDefinition("Name", "LONG", false, false), new TableDesignDialog.FieldDefinition("name", "LONG", false, false))), "Designer refuses ambiguous duplicate fields");
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("Name", ValueParser.parse("TEXT", "O'Brien — Zoë", true));
            values.put("Count", ValueParser.parse("LONG", "12", true));
            values.put("Ratio", ValueParser.parse("DOUBLE", "1.25", true));
            values.put("Cost", ValueParser.parse("MONEY", "49.99", true));
            values.put("Created", ValueParser.parse("SHORT_DATE_TIME", "2026-10-12 14:30:00", true));
            values.put("Enabled", ValueParser.parse("BOOLEAN", "Yes", true));
            check(db.insertRecord("UI Basic Types", values) == 1, "Typed UI insertion");
            var inserted = db.execute("SELECT * FROM [UI Basic Types]");
            List<Object> row = inserted.rows().get(0);
            Object key = row.get(inserted.columns().indexOf("ID"));
            check(row.get(inserted.columns().indexOf("Name")).equals("O'Brien — Zoë"), "Prepared Unicode/quote text is exact");
            check(new BigDecimal(row.get(inserted.columns().indexOf("Cost")).toString()).compareTo(new BigDecimal("49.99")) == 0, "Currency has exact decimal value");
            check(row.get(inserted.columns().indexOf("Created")).equals(Timestamp.valueOf("2026-10-12 14:30:00")), "Typed date/time is exact");
            check(row.get(inserted.columns().indexOf("Enabled")).equals(true), "Typed boolean is exact");
            check(db.updateRecord("UI Basic Types", Map.of("ID", key), "Name", "Edited") == 1, "Typed prepared cell update");
            check(db.updateRecord("UI Basic Types", Map.of("ID", key), "Name", null) == 1, "Explicit NULL cell update");
            check(db.execute("SELECT Name FROM [UI Basic Types]").rows().get(0).get(0) == null, "NULL remains SQL NULL");
            db.updateRecord("UI Basic Types", Map.of("ID", key), "Name", "O'Brien — Zoë");
            db.execute("ALTER TABLE [UI Basic Types] ADD COLUMN [Added Field] TEXT(255);");
            check(db.columns("UI Basic Types").stream().anyMatch(c -> c.name().equals("Added Field")), "Add-field command creates native field");

            ColumnInfo city = column(db, "Customers", "City"), customerId = column(db, "Customers", "CustomerID"), fullName = column(db, "Customers", "FullName");
            String aggregate = QueryDesignDialog.buildSql("Customers", List.of(
                new QueryDesignDialog.Projection("Customers", city, "(none)", "Ascending"),
                new QueryDesignDialog.Projection("Customers", customerId, "COUNT", "(none)")), false, null, null);
            var groups = db.execute(aggregate);
            check(groups.rows().size() == 2 && groups.rows().get(0).get(0).equals("Cape Town") && ((Number) groups.rows().get(0).get(1)).intValue() == 2, "Actual designer aggregate/group/order result");
            String filtered = QueryDesignDialog.buildSql("Customers", List.of(new QueryDesignDialog.Projection("Customers", fullName, "(none)", "Ascending")), false, null, new QueryDesignDialog.Filter("Customers", city, "=", "Cape Town"));
            var names = db.execute(filtered);
            check(names.columns().equals(List.of("FULLNAME")) || names.columns().equals(List.of("FullName")), "Designer projection contains only selected field");
            check(names.rows().size() == 2 && names.rows().get(0).get(0).equals("Amina Khan") && names.rows().get(1).get(0).equals("Zoë Dlamini"), "Actual designer filter and ascending order");
            String join = QueryDesignDialog.buildSql("Customers", List.of(
                new QueryDesignDialog.Projection("Customers", fullName, "(none)", "(none)"),
                new QueryDesignDialog.Projection("Orders", column(db, "Orders", "Amount"), "(none)", "Descending")), false,
                new QueryDesignDialog.Join("INNER JOIN", "Orders", "CustomerID", "CustomerID"), null);
            var joined = db.execute(join);
            check(joined.rows().size() == 3 && joined.rows().get(0).get(0).equals("Léa Müller") && new BigDecimal(joined.rows().get(0).get(1).toString()).compareTo(new BigDecimal("300")) == 0, "Designer equality join and descending amount");
            String dateFilter = QueryDesignDialog.buildSql("UI Basic Types", List.of(new QueryDesignDialog.Projection("UI Basic Types", column(db, "UI Basic Types", "ID"), "(none)", "(none)")), false, null,
                new QueryDesignDialog.Filter("UI Basic Types", column(db, "UI Basic Types", "Created"), "=", "2026-10-12 14:30:00"));
            check(db.execute(dateFilter).rows().size() == 1, "Designer generates an accepted Access date/time literal");
            String quoted = QueryDesignDialog.buildSql("UI Basic Types", List.of(new QueryDesignDialog.Projection("UI Basic Types", column(db, "UI Basic Types", "ID"), "(none)", "(none)")), false, null,
                new QueryDesignDialog.Filter("UI Basic Types", column(db, "UI Basic Types", "Name"), "=", "O'Brien — Zoë"));
            check(db.execute(quoted).rows().size() == 1, "Designer safely quotes apostrophes/Unicode filter text");
            check(db.execute("PARAMETERS [MinAmount] Currency; SELECT * FROM Orders WHERE Amount > [MinAmount];", List.of(ValueParser.parse("Currency", "100", false))).rows().size() == 2, "UI parameter value binds with currency type");
            check(!db.relationships().isEmpty(), "Relationship inspector receives native relationships");
            check(!AccessObjectCatalog.inspect(working).objects().isEmpty(), "Native object inspector receives catalog objects");
            rejects(() -> ValueParser.parse("LONG", "twelve", false), "Invalid numeric input is refused");
            rejects(() -> ValueParser.parse("BOOLEAN", "sometimes", false), "Invalid boolean input is refused");
            rejects(() -> ValueParser.parse("DATETIME", "2026-02-30", false), "Invalid date input is refused");
            check(db.deleteRecord("UI Basic Types", Map.of("ID", key)) == 1, "Prepared selected-record deletion");
            db.flush();
        }
        try (AccessDatabase reopened = AccessDatabase.openCopy(working, directory.resolve("reopened.accdb"))) {
            check(reopened.tables().contains("UI Basic Types"), "Designed native table survives reopen");
            check(reopened.columns("UI Basic Types").stream().anyMatch(c -> c.name().equals("Added Field")), "Added native field survives reopen");
            check(reopened.execute("SELECT * FROM [UI Basic Types]").rows().isEmpty(), "Record deletion persists in native ACCDB");
        }
        check(Arrays.equals(original, Files.readAllBytes(source)), "Source file is byte-for-byte unchanged");
        System.out.println("UI feature proof: " + assertions + " assertions PASS. " + directory);
    }
    private static ColumnInfo column(AccessDatabase db, String table, String name) throws Exception {
        return db.columns(table).stream().filter(c -> c.name().equalsIgnoreCase(name)).findFirst().orElseThrow();
    }
    private static void check(boolean passed, String name) { if (!passed) throw new AssertionError(name); assertions++; }
    private static void rejects(Runnable action, String name) {
        try { action.run(); } catch (IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError(name);
    }
}
