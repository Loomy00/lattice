package org.accessformac.objects;

import io.github.spannm.jackcess.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Real-file catalog checks; optional supplied runtime wizard references stay outside deliverables. */
public final class ObjectCatalogProof {
    private static int checks;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++; System.out.println("PASS " + message);
    }
    private static byte[] hash(Path p) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p));
    }
    public static void main(String[] args) throws Exception {
        Path scratch = Path.of("../../work/object-proof").toAbsolutePath().normalize();
        Files.createDirectories(scratch); Path dir = Files.createTempDirectory(scratch,"run-");
        Path sample = dir.resolve("catalog.accdb");
        try (Database db = new DatabaseBuilder(sample).withFileFormat(Database.FileFormat.V2010).create()) {
            Table table = new TableBuilder("Customer Notes")
                .addColumn(new ColumnBuilder("Name",DataType.TEXT))
                .addColumn(new ColumnBuilder("Notes",DataType.MEMO)).toTable(db);
            table.getProperties().put(PropertyMap.DESCRIPTION_PROP,"Catalog inspection fixture");
            table.getProperties().save();
            table.getColumn("Name").getProperties().put(PropertyMap.CAPTION_PROP,"Customer name");
            table.getColumn("Name").getProperties().save();
            table.addRow("Zoë 東京","Never execute application objects");
            // Deliberately absent backend: catalog inspection must never resolve this path.
            db.createLinkedTable("External","/not-present/access-object-proof.accdb","MissingTable");
        }
        byte[] before = hash(sample);
        var snapshot = AccessObjectCatalog.inspect(sample);
        check(snapshot.find("Customer Notes").orElseThrow().kind() == AccessObjectCatalog.Kind.TABLE,
            "Real native table catalog entry classified");
        check(snapshot.find("External").orElseThrow().kind() == AccessObjectCatalog.Kind.LINKED_TABLE,
            "Linked catalog metadata reads without opening missing backend");
        check(snapshot.objects().stream().anyMatch(o -> o.name().equals("MSysObjects") && o.system()),
            "Native system metadata distinguished from user tables");
        check(snapshot.applicationObjects().isEmpty(),"Empty native application containers are not invented objects");
        var properties = snapshot.tableProperties().stream().filter(p -> p.table().equals("Customer Notes")).findFirst().orElseThrow();
        check(properties.properties().get("Description").equals("Catalog inspection fixture"),"Native table description decoded");
        check(properties.columns().get("Name").get("Caption").equals("Customer name"),"Native field caption decoded");
        check(!snapshot.truncated(),"Small native catalog reports complete row coverage");
        check(Arrays.equals(before,hash(sample)),"Read-only catalog inspection preserves source SHA-256");
        check(snapshot.equals(AccessObjectCatalog.inspect(sample)),"Repeated read-only native metadata is stable");

        String reference = args.length > 0 ? args[0] : System.getenv("ACCESS_RUNTIME_REFERENCE_DIR");
        if (reference == null || reference.isBlank()) {
            System.out.println("SKIP supplied-runtime form/module/report checks: set ACCESS_RUNTIME_REFERENCE_DIR or pass its targets directory.");
        } else {
            Path main = Path.of(reference).resolve("ACWZMAIN.ACCDE");
            Path user = Path.of(reference).resolve("ACWZUSR12.ACCDU");
            byte[] mainHash = hash(main), userHash = hash(user);
            var nativeMain = AccessObjectCatalog.inspect(main);
            var nativeUser = AccessObjectCatalog.inspect(user);
            long forms = nativeMain.applicationObjects().stream().filter(o -> o.kind() == AccessObjectCatalog.Kind.FORM).count();
            long modules = nativeMain.applicationObjects().stream().filter(o -> o.kind() == AccessObjectCatalog.Kind.MODULE).count();
            check(forms == 66 && modules == 34,"Supplied Runtime wizard catalog enumerates 66 real forms and 34 modules");
            check(nativeUser.find("mlbl_BarCodeTemp").orElseThrow().kind() == AccessObjectCatalog.Kind.REPORT,
                "Supplied Runtime user wizard report enumerates");
            check(nativeMain.storageNodes().size() == 726,"Actual modern Access storage tree enumerates 726 rows");
            check(nativeMain.storageNodes().stream().anyMatch(n -> !n.blobs().isEmpty()),"Opaque native storage payload SHA-256/length evidence available");
            check(!nativeMain.truncated() && !nativeUser.truncated(),"Runtime reference catalogs/storage within complete row limit");
            Path copy = dir.resolve("wizard-copy.accdb"); Files.copy(main,copy);
            check(nativeMain.equals(AccessObjectCatalog.inspect(copy)),"Native object/storage inventory identical in byte-for-byte copied runtime fixture");
            try (Database db = new DatabaseBuilder(copy).open()) {
                new TableBuilder("IndependentCatalogProof")
                    .addColumn(new ColumnBuilder("ID",DataType.LONG)).toTable(db).addRow(1);
            }
            var changed = AccessObjectCatalog.inspect(copy);
            check(nativeMain.applicationObjects().equals(changed.applicationObjects()),
                "All supplied form/module catalog metadata fingerprints survive unrelated native table addition");
            check(nativeMain.storageNodes().equals(changed.storageNodes()),
                "All 726 native storage row metadata/payload fingerprints survive unrelated native table addition");
            check(changed.find("IndependentCatalogProof").isPresent(),"Disposable runtime copy receives native table addition");
            check(Arrays.equals(mainHash,hash(main)) && Arrays.equals(userHash,hash(user)),"Supplied Runtime references remain unchanged SHA-256");
            System.out.println("LIMIT: Object/storage fingerprints are preservation evidence, not form rendering, VBA execution, or Windows reopen proof.");
        }
        System.out.println("RESULT: " + checks + " native object-catalog assertions passed.");
        System.out.println("Evidence directory: " + dir);
    }
}
