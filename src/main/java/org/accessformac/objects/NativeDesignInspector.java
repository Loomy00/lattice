package org.accessformac.objects;

import io.github.spannm.jackcess.*;
import java.io.IOException;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.Path;
import java.security.*;
import java.util.*;

/** Experimental, read-only inspection of observed Access 2016 design streams.
 * This exposes flat source records, including defaults, not a rendering or execution model.
 * Format leads: https://github.com/WilliamSmithEdward/pyOpenVBA/blob/main/src/pyopenvba/access/_designs.py
 * Independently implemented and checked against the supplied Microsoft runtime fixtures.
 */
public final class NativeDesignInspector {
    public static final int BYTE_LIMIT = 16 * 1024 * 1024, RECORD_LIMIT = 20000;
    public record Property(int offset, long id, int code, long valueType, long width,
                           int valueOffset, int valueLength, String valueSha256, Optional<String> text) {}
    public record Segment(int offset, Integer marker, Integer markerCode, Integer type, List<Property> properties) {
        public Segment { properties = List.copyOf(properties); }
        public Optional<String> nameCandidate() {
            return properties.stream().filter(p -> p.code() == 20 || p.code() == 21)
                .map(Property::text).flatMap(Optional::stream).findFirst();
        }
    }
    public record Design(String objectName, String container, String storageFolder, Long storageRowId,
                         int bytes, String sha256, List<Segment> segments, int parsedThrough,
                         boolean structurallyComplete, List<String> diagnostics) {
        public Design { segments = List.copyOf(segments); diagnostics = List.copyOf(diagnostics); }
        /** Excludes unnamed defaults; these are candidates, not a validated Access Controls collection. */
        public List<Segment> namedControlCandidates() {
            return segments.stream().filter(s -> s.type() != null && s.type() >= 100 && s.type() < 152
                && s.nameCandidate().isPresent()).toList();
        }
    }
    private NativeDesignInspector() {}

    public static Design inspect(Path path, String container, String objectName) throws IOException {
        if (!Set.of("Forms", "Reports").contains(container)) throw new IOException("Choose Forms or Reports.");
        try (Database db = new DatabaseBuilder(path).withReadOnly(true).open()) {
            db.setLinkResolver((database, link) -> { throw new IOException("External links are disabled."); });
            Table table = db.getSystemTable("MSysAccessStorage");
            if (table == null) throw new IOException("This database has no observed MSysAccessStorage design streams.");
            List<Row> rows = new ArrayList<>();
            // Project metadata: never load every design/VBA Lv value while locating one object.
            for (Row row : table.getDefaultCursor().newIterable().withColumnNames(List.of("Id", "ParentId", "Name"))) {
                if (rows.size() >= RECORD_LIMIT) throw new IOException("Storage row limit exceeded."); rows.add(row);
            }
            Row root = unique(rows, null, container);
            Row directory = unique(rows, root.get("Id"), "\u0003DirData");
            byte[] directoryBytes = selectedPayload(table, directory);
            String folder = directory(directoryBytes).get(objectName);
            if (folder == null) throw new IOException("Object not present in the native design directory: " + objectName);
            Row folderRow = unique(rows, root.get("Id"), folder);
            Row blob = unique(rows, folderRow.get("Id"), "Blob");
            byte[] bytes = selectedPayload(table, blob);
            if ((long)bytes.length + directoryBytes.length > BYTE_LIMIT) throw new IOException("Selected design and directory exceed the total inspection byte budget.");
            Design decoded = decode(bytes);
            Long id = blob.get("Id") instanceof Number number ? number.longValue() : null;
            return new Design(objectName, container, folder, id, decoded.bytes(), decoded.sha256(), decoded.segments(),
                decoded.parsedThrough(), decoded.structurallyComplete(), decoded.diagnostics());
        }
    }
    private static byte[] selectedPayload(Table table, Row projected) throws IOException {
        Cursor cursor = table.newCursor().toCursor();
        if (!cursor.findRow(projected.getId())) throw new IOException("Selected storage row disappeared.");
        // Jackcess materializes this one long value before exposing its bytes; the API has no
        // length-only/streaming getter. The limit bounds our retained/decoded bytes, not that allocation.
        Object raw = cursor.getCurrentRowValue(table.getColumn("Lv"));
        if (!(raw instanceof byte[] bytes)) throw new IOException("Missing selected native storage payload.");
        if (bytes.length > BYTE_LIMIT) throw new IOException("Selected storage payload exceeds the inspection byte limit.");
        return bytes;
    }
    private static Row unique(List<Row> rows, Object parent, String name) throws IOException {
        List<Row> matches = rows.stream().filter(r -> name.equals(r.get("Name"))
            && (parent == null || Objects.equals(parent, r.get("ParentId")))).toList();
        if (matches.size() != 1) throw new IOException("Missing or ambiguous storage node: " + name);
        return matches.get(0);
    }
    /** Native directory: zero u32, then tag04/u8 length/UTF16LE name/u32 numeric folder. */
    static Map<String, String> directory(byte[] bytes) throws IOException {
        if (bytes.length < 4 || bytes.length > BYTE_LIMIT || u32(bytes, 0) != 0) throw new IOException("Unrecognized design directory header.");
        Map<String, String> entries = new LinkedHashMap<>();
        for (int at = 4; at < bytes.length;) {
            if (bytes.length - at < 2 || bytes[at] != 4) throw new IOException("Unrecognized directory entry at " + at);
            int length = Byte.toUnsignedInt(bytes[at + 1]);
            if (length < 6 || (length - 4) % 2 != 0 || length > bytes.length - at - 2)
                throw new IOException("Invalid directory entry length at " + at);
            String name = text(bytes, at + 2, length - 4);
            String folder = Long.toString(u32(bytes, at + 2 + length - 4));
            if (entries.putIfAbsent(name, folder) != null) throw new IOException("Duplicate design directory name.");
            if (entries.size() > RECORD_LIMIT) throw new IOException("Design directory limit exceeded.");
            at += 2 + length;
        }
        return Collections.unmodifiableMap(entries);
    }
    /** Structural decoding only. Unknown or malformed data returns partial records with explicit status. */
    public static Design decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > BYTE_LIMIT) throw new IOException("Native design exceeds the inspection byte limit.");
        String sha = hash(bytes, 0, bytes.length); List<String> diagnostics = new ArrayList<>();
        List<Segment> segments = new ArrayList<>(); List<Property> properties = new ArrayList<>();
        int at = 10, segmentOffset = 10, count = 0; Integer marker = null, markerCode = null, type = null;
        if (bytes.length < 14 || u32(bytes, 0) != 0x00140014L)
            return new Design("", "", "", null, bytes.length, sha, List.of(), 0, false, List.of("Unrecognized design header; no records decoded."));
        boolean complete = false;
        while (at < bytes.length) {
            int remaining = bytes.length - at;
            if (remaining == 4 && u32(bytes, at) == 0xfd) { complete = true; break; }
            if (remaining < 6) { diagnostics.add("Incomplete record/marker at byte " + at); break; }
            long id = u32(bytes, at); int code = u16(bytes, at + 4);
            if (id == 0xfd || id == 0xfe || id == 0xff) {
                int markerLength = id == 0xff ? 8 : 6;
                if (remaining < markerLength) { diagnostics.add("Incomplete marker at byte " + at); break; }
                if (++count > RECORD_LIMIT) { diagnostics.add("Inspection record limit reached at byte " + at); break; }
                segments.add(new Segment(segmentOffset, marker, markerCode, type, properties)); properties = new ArrayList<>();
                segmentOffset = at; marker = (int)id; markerCode = code; type = id == 0xff ? u16(bytes, at + 6) : code;
                at += markerLength; continue;
            }
            if (remaining < 18) { diagnostics.add("Incomplete property header at byte " + at); break; }
            long valueType = u32(bytes, at + 6), width = u32(bytes, at + 10), length = u32(bytes, at + 14);
            if (valueType > 0xffff || length > remaining - 18L) { diagnostics.add("Invalid property bounds/type at byte " + at); break; }
            if (++count > RECORD_LIMIT) { diagnostics.add("Inspection record limit reached at byte " + at); break; }
            Optional<String> valueText = Optional.empty();
            if (valueType == 10 || valueType == 12) {
                try { valueText = Optional.of(text(bytes, at + 18, (int)length)); }
                catch (IOException malformed) { diagnostics.add("Invalid UTF16 property at byte " + at + "; raw fingerprint retained."); }
            }
            properties.add(new Property(at, id, code, valueType, width, at + 18, (int)length,
                hash(bytes, at + 18, (int)length), valueText));
            at += 18 + (int)length;
        }
        segments.add(new Segment(segmentOffset, marker, markerCode, type, properties));
        if (!complete && diagnostics.isEmpty()) diagnostics.add("Missing observed closing marker at byte " + at);
        diagnostics.add("Property code meanings, nesting, defaults, events and execution are not fully validated; no RecordSource inference.");
        return new Design("", "", "", null, bytes.length, sha, segments, at, complete, diagnostics);
    }
    private static long u32(byte[] bytes, int offset) { return Integer.toUnsignedLong(ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()); }
    private static int u16(byte[] bytes, int offset) { return Short.toUnsignedInt(ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort()); }
    private static String text(byte[] bytes, int offset, int length) throws IOException {
        if (length % 2 != 0) throw new IOException("Odd UTF16 byte length.");
        try { return StandardCharsets.UTF_16LE.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, offset, length)).toString(); }
        catch (CharacterCodingException malformed) { throw new IOException("Malformed UTF16 property.", malformed); }
    }
    private static String hash(byte[] bytes, int offset, int length) {
        try { MessageDigest digest = MessageDigest.getInstance("SHA-256"); digest.update(bytes, offset, length); return HexFormat.of().formatHex(digest.digest()); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
