package org.accessformac.db;
import io.github.spannm.jackcess.*;
import io.github.spannm.jackcess.impl.query.QueryFormat;
import java.nio.file.*;
import java.util.*;
/** Explicit exploratory catalog serialization, kept out of production until reviewed. */
public final class NativeQueryCatalogProbe {
    static void queryRow(Table rows,int id,byte attribute,String expression,String name1,String name2,Short flag,int sequence)throws Exception {
        Map<String,Object> row=new HashMap<>();row.put("ObjectId",id);row.put("Attribute",attribute);row.put("Expression",expression);
        row.put("Name1",name1);row.put("Name2",name2);row.put("Flag",flag);
        row.put("Order",java.nio.ByteBuffer.allocate(4).putInt(sequence).array());rows.addRowFromMap(row);
    }
    public static void main(String[] args)throws Exception {
        Path scratch=Files.createTempDirectory(Path.of("../../work/database-proof"),"native-query-probe-");
        Path target=scratch.resolve("native-select.accdb");Files.copy(Path.of("fixtures/OriginalSample.accdb"),target);
        int id=-1000000000;
        try(Database db=new DatabaseBuilder(target).open()) {
            Table catalog=db.getSystemTable("MSysObjects");Object tablesId=null,owner=null;Object originalTableId=null;
            for(Row row:catalog){if("Tables".equals(row.get("Name")))tablesId=row.get("Id");if("Customers".equals(row.get("Name"))){owner=row.get("Owner");originalTableId=row.get("Id");}}
            Map<String,Object> query=new HashMap<>();query.put("Id",id);query.put("ParentId",tablesId);query.put("Name","ExperimentalNativeSummary");query.put("Type",(short)5);query.put("Flags",0);query.put("Owner",owner);query.put("DateCreate",java.time.LocalDateTime.now());query.put("DateUpdate",java.time.LocalDateTime.now());catalog.addRowFromMap(query);
            Table rows=db.getSystemTable("MSysQueries");
            queryRow(rows,id,QueryFormat.START_ATTRIBUTE,null,null,null,(short)0,0);
            queryRow(rows,id,QueryFormat.TYPE_ATTRIBUTE,null,null,null,(short)1,1);
            queryRow(rows,id,QueryFormat.FLAG_ATTRIBUTE,null,null,null,(short)0,2);
            queryRow(rows,id,QueryFormat.TABLE_ATTRIBUTE,null,"Customers",null,null,3);
            queryRow(rows,id,QueryFormat.COLUMN_ATTRIBUTE,"City",null,null,(short)0,4);
            queryRow(rows,id,QueryFormat.COLUMN_ATTRIBUTE,"Count(*)","CustomerCount",null,(short)0,5);
            queryRow(rows,id,QueryFormat.GROUPBY_ATTRIBUTE,"City",null,null,null,6);
            queryRow(rows,id,QueryFormat.ORDERBY_ATTRIBUTE,"City",null,null,null,7);
            queryRow(rows,id,QueryFormat.END_ATTRIBUTE,null,null,null,null,8);
            Table aces=db.getSystemTable("MSysACEs");List<Map<String,Object>> copies=new ArrayList<>();
            for(Row row:aces)if(Objects.equals(originalTableId,row.get("ObjectId"))){Map<String,Object> copy=new HashMap<>(row);copy.put("ObjectId",id);copies.add(copy);}
            for(Map<String,Object> row:copies)aces.addRowFromMap(row);
            System.out.println("Native query rows created with inherited table permissions: "+copies.size());
            db.flush();System.out.println(db.getQueries().get(0).toSQLString());
        }
        try(Database db=new DatabaseBuilder(target).withReadOnly(true).open()) {
            if(db.getQueries().size()!=1)throw new AssertionError("Native saved-query count");
            if(db.getQueries().get(0).getType()!=io.github.spannm.jackcess.query.Query.Type.SELECT)throw new AssertionError("Native saved-query type");
            System.out.println("PASS Native SELECT query catalog independently reopened: "+db.getQueries().get(0).toSQLString());
        }
        try(AccessDatabase db=AccessDatabase.openCopy(target,scratch.resolve("jdbc-reopen.accdb"))) {
            var result=db.execute("SELECT * FROM ExperimentalNativeSummary ORDER BY City");
            if(result.rows().size()!=2)throw new AssertionError("Saved query result count");
            System.out.println("PASS Reopened native query name executes in JDBC: "+result.rows());
        }
        System.out.println("Exploratory file: "+target);
        System.out.println("NOT VERIFIED in Windows Access2016; catalog encoding uses pinned Jackcess source and local fixture conventions.");
    }
}
