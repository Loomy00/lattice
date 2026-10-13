package org.accessformac.objects;

import io.github.spannm.jackcess.*;
import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Read-only native catalog inspection. This is not an Access form/VBA decoder. */
public final class AccessObjectCatalog {
    private static final int ROW_LIMIT = 20_000;
    private AccessObjectCatalog() {}

    public enum Kind { TABLE, LINKED_TABLE, QUERY, FORM, REPORT, MACRO, MODULE, CONTAINER, RELATIONSHIP, OTHER }
    public record BlobFingerprint(int bytes, String sha256) {}
    public record NativeObject(Long id, Long parentId, String name, Integer rawType,
            Integer flags, Kind kind, boolean system, Map<String,String> metadata,
            Map<String,BlobFingerprint> blobs) {
        public NativeObject {
            metadata = Map.copyOf(metadata); blobs = Map.copyOf(blobs);
        }
        public boolean applicationObject() {
            return kind == Kind.FORM || kind == Kind.REPORT || kind == Kind.MACRO || kind == Kind.MODULE;
        }
    }
    /** Storage rows describe a native tree; payload bytes remain opaque and are never modified. */
    public record StorageNode(String storageTable, Long id, Long parentId, String name,
            Integer rawType, Map<String,String> metadata, Map<String,BlobFingerprint> blobs) {
        public StorageNode { metadata = Map.copyOf(metadata); blobs = Map.copyOf(blobs); }
    }
    public record TableProperties(String table, Map<String,String> properties,
            Map<String,Map<String,String>> columns) {
        public TableProperties {
            properties = Map.copyOf(properties);
            Map<String,Map<String,String>> copy = new LinkedHashMap<>();
            columns.forEach((k,v) -> copy.put(k,Map.copyOf(v))); columns = Map.copyOf(copy);
        }
    }
    public record Snapshot(String fileFormat, List<NativeObject> objects,
            List<StorageNode> storageNodes, List<TableProperties> tableProperties,
            List<String> diagnostics, boolean truncated) {
        public Snapshot {
            objects = List.copyOf(objects); storageNodes = List.copyOf(storageNodes);
            tableProperties = List.copyOf(tableProperties); diagnostics = List.copyOf(diagnostics);
        }
        public List<NativeObject> applicationObjects() {
            return objects.stream().filter(NativeObject::applicationObject).toList();
        }
        public Optional<NativeObject> find(String name) {
            return objects.stream().filter(o -> o.name().equalsIgnoreCase(name)).findFirst();
        }
    }

    /** Opens the exact file read-only; never resolves linked databases or executes saved objects. */
    public static Snapshot inspect(Path file) throws IOException {
        Objects.requireNonNull(file,"file");
        if (!Files.isRegularFile(file)) throw new IOException("Not a regular Access file: " + file);
        List<NativeObject> objects = new ArrayList<>();
        List<StorageNode> storage = new ArrayList<>();
        List<TableProperties> properties = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        boolean truncated = false;
        try (Database db = new DatabaseBuilder(file.toAbsolutePath()).withReadOnly(true).open()) {
            Set<String> systemNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            systemNames.addAll(db.getSystemTableNames());
            Table catalog = db.getSystemTable("MSysObjects");
            if (catalog == null) throw new IOException("Native MSysObjects catalog is absent.");
            for (Row row : catalog) {
                if (objects.size() == ROW_LIMIT) { truncated = true; break; }
                String name = text(row.get("Name"));
                Integer type = integer(row.get("Type"));
                Kind kind = classify(type);
                Map<String,String> metadata = new LinkedHashMap<>();
                Map<String,BlobFingerprint> blobs = new LinkedHashMap<>();
                describe(row,metadata,blobs);
                objects.add(new NativeObject(number(row.get("Id")),number(row.get("ParentId")),
                    name,type,integer(row.get("Flags")),kind,systemNames.contains(name),metadata,blobs));
            }
            objects.sort(Comparator.comparing((NativeObject o) -> o.kind().name())
                .thenComparing(NativeObject::name,String.CASE_INSENSITIVE_ORDER));
            // These known native storage tables are read directly, never through JDBC.
            for (String tableName : List.of("MSysAccessStorage","MSysAccessObjects")) {
                Table table = db.getSystemTable(tableName);
                if (table == null) continue;
                int count = 0;
                for (Row row : table) {
                    if (count++ == ROW_LIMIT) { truncated = true; break; }
                    Map<String,String> metadata = new LinkedHashMap<>();
                    Map<String,BlobFingerprint> blobs = new LinkedHashMap<>();
                    describe(row,metadata,blobs);
                    storage.add(new StorageNode(tableName,number(row.get("Id")),number(row.get("ParentId")),
                        text(row.get("Name")),integer(row.get("Type")),metadata,blobs));
                }
            }
            for (NativeObject object : objects) {
                if (object.system() || object.kind() != Kind.TABLE) continue;
                try {
                    TableMetaData info = db.getTableMetaData(object.name());
                    if (info == null || info.isLinked()) continue;
                    Table table = db.getTable(object.name());
                    Map<String,Map<String,String>> columns = new LinkedHashMap<>();
                    for (Column c : table.getColumns()) columns.put(c.getName(),describe(c.getProperties()));
                    properties.add(new TableProperties(table.getName(),describe(table.getProperties()),columns));
                } catch (IOException | RuntimeException unavailable) {
                    diagnostics.add("Properties unavailable for " + object.name() + ": " + unavailable.getMessage());
                }
            }
            diagnostics.add("Forms, reports, macros and modules are catalog entries only. Design decoding, rendering, execution and native object editing are unavailable.");
            diagnostics.add("Binary fields are SHA-256 fingerprints, not decoded properties or proof that all object data lives in the catalog/storage tables.");
            if (truncated) diagnostics.add("Catalog/storage inspection truncated at " + ROW_LIMIT + " rows per table.");
            return new Snapshot(db.getFileFormat().toString(),objects,storage,properties,diagnostics,truncated);
        }
    }

    private static Kind classify(Integer type) {
        if (type == null) return Kind.OTHER;
        return switch (type) {
            case 1 -> Kind.TABLE;
            case 4, 6 -> Kind.LINKED_TABLE;
            case 5 -> Kind.QUERY;
            case 3 -> Kind.CONTAINER;
            case 8 -> Kind.RELATIONSHIP;
            // Observed against supplied runtime wizard forms/reports/modules;
            // macro -32766 is a conventional candidate pending a native macro fixture.
            case -32768 -> Kind.FORM;
            case -32764 -> Kind.REPORT;
            case -32766 -> Kind.MACRO;
            case -32761 -> Kind.MODULE;
            default -> Kind.OTHER;
        };
    }
    private static void describe(Map<String,?> values, Map<String,String> scalar,
            Map<String,BlobFingerprint> blobs) {
        for (Map.Entry<String,?> e : values.entrySet()) {
            Object v = e.getValue();
            if (v instanceof byte[] bytes) blobs.put(e.getKey(),fingerprint(bytes));
            else if (v == null) scalar.put(e.getKey(),"<null>");
            else if (e.getKey().equalsIgnoreCase("Connect"))
                scalar.put(e.getKey(),"<connection metadata retained; value hidden>");
            else scalar.put(e.getKey(),text(v));
        }
    }
    private static Map<String,String> describe(PropertyMap values) {
        Map<String,String> result = new LinkedHashMap<>();
        for (PropertyMap.Property p : values) {
            Object v = p.getValue();
            result.put(p.getName(),v instanceof byte[] b ? "Binary " + fingerprint(b) : text(v));
        }
        return result;
    }
    private static BlobFingerprint fingerprint(byte[] bytes) {
        try { return new BlobFingerprint(bytes.length,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String text(Object value) { return value == null ? "<null>" : String.valueOf(value); }
    private static Integer integer(Object value) { return value instanceof Number n ? n.intValue() : null; }
    private static Long number(Object value) { return value instanceof Number n ? n.longValue() : null; }
}
