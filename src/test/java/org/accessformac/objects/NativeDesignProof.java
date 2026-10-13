package org.accessformac.objects;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Bounded structure checks plus optional actual Microsoft runtime fixture inspection. */
public final class NativeDesignProof {
    private static int passed;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message); passed++; System.out.println("PASS " + message);
    }
    private static void property(ByteArrayOutputStream out, int id, int code, String text) throws IOException {
        byte[] value = text.getBytes(StandardCharsets.UTF_16LE);
        out.write(ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN).putInt(id).putShort((short)code)
            .putInt(10).putInt(4).putInt(value.length).array()); out.write(value);
    }
    public static void main(String[] args) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{20,0,20,0,0,0,0,0,(byte)150,0});
        property(out,419,17,"Caption"); property(out,271,21,"Nonascending ✓");
        out.write(ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putInt(0xfd).putShort((short)104).array());
        property(out,220,20,"btnOK"); out.write(new byte[]{(byte)253,0,0,0});
        byte[] bytes = out.toByteArray(); var decoded = NativeDesignInspector.decode(bytes);
        check(decoded.structurallyComplete(),"Observed closing marker and all bounded records recognized");
        check(decoded.segments().get(0).properties().get(1).text().orElseThrow().equals("Nonascending ✓"),"Nonascending property IDs do not truncate valid streams");
        check(decoded.namedControlCandidates().size()==1,"Named control candidates exclude root records");
        var property=decoded.segments().get(1).properties().get(0);
        check(property.valueOffset()==property.offset()+18 && property.valueLength()==10,"Property exposes exact source byte offsets and lengths");
        byte[] corrupt=bytes.clone();ByteBuffer.wrap(corrupt,24,4).order(ByteOrder.LITTLE_ENDIAN).putInt(-1);
        check(!NativeDesignInspector.decode(corrupt).structurallyComplete(),"Unsigned oversized property length rejected without allocation");
        check(!NativeDesignInspector.decode(Arrays.copyOf(bytes,bytes.length-2)).structurallyComplete(),"Truncated marker explicitly incomplete");
        check(NativeDesignInspector.decode(new byte[14]).segments().isEmpty(),"Unknown header produces no invented design records");
        byte[] odd=bytes.clone();ByteBuffer.wrap(odd,24,4).order(ByteOrder.LITTLE_ENDIAN).putInt(1);
        check(NativeDesignInspector.decode(odd).segments().get(0).properties().get(0).text().isEmpty(),"Malformed UTF16 is not guessed as text");
        boolean limited=false;try{NativeDesignInspector.decode(new byte[NativeDesignInspector.BYTE_LIMIT+1]);}catch(IOException expected){limited=true;}
        check(limited,"Byte inspection limit enforced");
        ByteArrayOutputStream dir=new ByteArrayOutputStream();dir.write(new byte[4]);byte[] name="af_frmAutoformat".getBytes(StandardCharsets.UTF_16LE);
        dir.write(4);dir.write(name.length+4);dir.write(name);dir.write(new byte[4]);
        check(NativeDesignInspector.directory(dir.toByteArray()).get("af_frmAutoformat").equals("0"),"Length-delimited native directory maps name to numbered folder");
        boolean directoryRefused=false;try{NativeDesignInspector.directory(Arrays.copyOf(dir.toByteArray(),dir.size()-1));}catch(IOException expected){directoryRefused=true;}
        check(directoryRefused,"Truncated native directory refuses ambiguous mapping");
        String reference=args.length>0?args[0]:System.getenv("ACCESS_RUNTIME_REFERENCE_DIR");
        if(reference==null||reference.isBlank())System.out.println("SKIP Actual runtime designs: ACCESS_RUNTIME_REFERENCE_DIR not set.");
        else {
            Path main=Path.of(reference,"ACWZMAIN.ACCDE"),usr=Path.of(reference,"ACWZUSR12.ACCDU");
            byte[] mainHash=hash(main),usrHash=hash(usr);
            var form=NativeDesignInspector.inspect(main,"Forms","af_frmAutoformat");
            check(form.structurallyComplete() && form.parsedThrough()==6569 && form.bytes()==6573,"Actual2016 form complete record framing through byte6569");
            check(form.storageRowId()==54 && form.storageFolder().equals("0"),"Actual native directory maps form to Blob row54/folder0");
            check(form.segments().stream().mapToInt(s->s.properties().size()).sum()==249,"Actual form contains249 bounded property records");
            check(form.namedControlCandidates().size()==13,"Actual form yields13 named control candidates, excluding defaults and section");
            check(form.namedControlCandidates().stream().anyMatch(s->s.nameCandidate().orElse("").equals("btnOK")),"Actual length-delimited btnOK name recovered");
            check(form.segments().get(0).properties().stream().anyMatch(p->p.offset()==495&&p.code()==17&&p.text().orElse("").equals("AutoFormat")),"Actual caption candidate recovered at sourcebyte495");
            var report=NativeDesignInspector.inspect(usr,"Reports","mlbl_BarCodeTemp");
            check(report.bytes()>0 && !report.segments().isEmpty(),"Actual runtime report structure inspected without renderer");
            System.out.println("REPORT complete="+report.structurallyComplete()+" bytes="+report.bytes()+" parsed="+report.parsedThrough()+" segments="+report.segments().size()+" controls="+report.namedControlCandidates().size()+" diagnostics="+report.diagnostics());
            check(Arrays.equals(mainHash,hash(main))&&Arrays.equals(usrHash,hash(usr)),"Both supplied runtime fixtures unchanged SHA256");
        }
        System.out.println("RESULT: "+passed+" native design inspection assertions passed; execution/Windows property comparison NOT performed.");
    }
    private static byte[] hash(Path path)throws Exception{return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));}
}
