package org.accessformac.db;

import io.github.spannm.jackcess.*;
import io.github.spannm.jackcess.impl.query.*;
import io.github.spannm.jackcess.query.Query;
import java.sql.SQLException;
import java.util.*;
import static io.github.spannm.jackcess.impl.query.QueryFormat.*;

/** Experimental native SELECT serialization, tied to pinned Jackcess 5.1.7's public query row model. */
final class NativeSelectQuery {
    record Definition(String table, List<QueryImpl.Row> rows, String rendered) {}
    static Definition parse(String name,String input,int objectId)throws Exception {
        String sql=input.strip();if(sql.endsWith(";"))sql=sql.substring(0,sql.length()-1).strip();
        List<SqlSupport.Token> tokens=SqlSupport.lex(sql);
        if(tokens.isEmpty()||!keyword(sql,tokens.get(0),"SELECT"))throw unsupported();
        int depth=0;Map<String,Integer> clauses=new LinkedHashMap<>();
        for(int i=1;i<tokens.size();i++) {
            var t=tokens.get(i);
            if(t.value().equals("("))depth++;if(t.value().equals(")"))depth--;
            if(depth==0 && !t.literal() && sql.charAt(t.start())!='[') {
                String word=t.value().toUpperCase(Locale.ROOT);
                if(Set.of("FROM","WHERE","HAVING").contains(word)) {
                    if(clauses.put(word,i)!=null)throw unsupported();
                }
                if(Set.of("GROUP","ORDER").contains(word)&&i+1<tokens.size()&&keyword(sql,tokens.get(i+1),"BY")) {
                    if(clauses.put(word,i)!=null)throw unsupported();
                }
                if(Set.of("INTO","UNION","TOP","DISTINCTROW","JOIN").contains(word))throw unsupported();
            }
        }
        Integer from=clauses.get("FROM");if(from==null)throw unsupported();
        List<String> order=new ArrayList<>(clauses.keySet());List<String> expected=List.of("FROM","WHERE","GROUP","HAVING","ORDER");
        int prev=-1;for(String clause:order){int current=expected.indexOf(clause);if(current<=prev)throw unsupported();prev=current;}
        int sourceEnd=nextStart(sql,tokens,clauses,"FROM");String source=sql.substring(tokens.get(from).end(),sourceEnd).strip();
        List<SqlSupport.Token> tableTokens=SqlSupport.lex(source);
        if(tableTokens.isEmpty()||!tableTokens.get(0).identifier()||tableTokens.get(0).literal())throw unsupported();
        String table=tableTokens.get(0).value(),alias=null;
        if(tableTokens.size()==2&&tableTokens.get(1).identifier())alias=tableTokens.get(1).value();
        else if(tableTokens.size()==3&&tableTokens.get(1).value().equalsIgnoreCase("AS")&&tableTokens.get(2).identifier())alias=tableTokens.get(2).value();
        else if(tableTokens.size()!=1)throw unsupported();
        List<QueryImpl.Row> rows=new ArrayList<>();short flags=0;int selectStart=tokens.get(0).end();
        if(tokens.size()>1&&keyword(sql,tokens.get(1),"DISTINCT")){flags=DISTINCT_SELECT_TYPE;selectStart=tokens.get(1).end();}
        add(rows,objectId,START_ATTRIBUTE,null,null,null,(short)0);
        add(rows,objectId,TYPE_ATTRIBUTE,null,null,null,Query.Type.SELECT.getValue());
        add(rows,objectId,FLAG_ATTRIBUTE,null,null,null,flags);
        add(rows,objectId,TABLE_ATTRIBUTE,null,table,alias,null);
        for(String item:split(sql.substring(selectStart,tokens.get(from).start()))) {
            List<SqlSupport.Token> expressionTokens=SqlSupport.lex(item);int as=-1;int nested=0;
            for(int i=0;i<expressionTokens.size();i++) {
                var token=expressionTokens.get(i);if(token.value().equals("("))nested++;if(token.value().equals(")"))nested--;
                if(nested==0&&keyword(item,token,"AS"))as=i;
            }
            String expression=item,fieldAlias=null;
            if(as>=0){if(as+2!=expressionTokens.size()||!expressionTokens.get(as+1).identifier())throw unsupported();fieldAlias=expressionTokens.get(as+1).value();expression=item.substring(0,expressionTokens.get(as).start()).strip();}
            if(expression.isBlank())throw unsupported();add(rows,objectId,COLUMN_ATTRIBUTE,expression,fieldAlias,null,(short)0);
        }
        for(var clause:clauses.entrySet()) {
            if(clause.getKey().equals("FROM"))continue;
            int i=clause.getValue();int start=tokens.get(i).end();
            if(clause.getKey().equals("GROUP")||clause.getKey().equals("ORDER"))start=tokens.get(i+1).end();
            String expression=sql.substring(start,nextStart(sql,tokens,clauses,clause.getKey())).strip();
            if(expression.isEmpty())throw unsupported();
            switch(clause.getKey()) {
                case "WHERE" -> add(rows,objectId,WHERE_ATTRIBUTE,expression,null,null,null);
                case "HAVING" -> add(rows,objectId,HAVING_ATTRIBUTE,expression,null,null,null);
                case "GROUP" -> {for(String item:split(expression))add(rows,objectId,GROUPBY_ATTRIBUTE,item,null,null,null);}
                case "ORDER" -> {for(String item:split(expression)) {
                    List<SqlSupport.Token> itemTokens=SqlSupport.lex(item);var last=itemTokens.get(itemTokens.size()-1);String descending=null;
                    if(keyword(item,last,"DESC")||keyword(item,last,"ASC")){if(last.value().equalsIgnoreCase("DESC"))descending=DESCENDING_FLAG;item=item.substring(0,last.start()).strip();}
                    add(rows,objectId,ORDERBY_ATTRIBUTE,item,descending,null,null);
                }}
                default -> throw unsupported();
            }
        }
        add(rows,objectId,END_ATTRIBUTE,null,null,null,null);
        SelectQueryImpl query=new SelectQueryImpl(name,rows,objectId,SELECT_QUERY_OBJECT_FLAG);
        return new Definition(table,List.copyOf(rows),query.toSQLString());
    }
    private static boolean keyword(String sql,SqlSupport.Token token,String expected) {
        return !token.literal()&&sql.charAt(token.start())!='['&&token.value().equalsIgnoreCase(expected);
    }
    private static int nextStart(String sql,List<SqlSupport.Token> tokens,Map<String,Integer> clauses,String clause) {
        int current=clauses.get(clause);return clauses.values().stream().filter(i->i>current).mapToInt(i->tokens.get(i).start()).min().orElse(sql.length());
    }
    private static List<String> split(String sql)throws SQLException {
        List<String> items=new ArrayList<>();int depth=0,start=0;
        for(var token:SqlSupport.lex(sql)){if(token.value().equals("("))depth++;if(token.value().equals(")"))depth--;if(depth==0&&token.value().equals(",")){items.add(sql.substring(start,token.start()).strip());start=token.end();}}
        items.add(sql.substring(start).strip());if(items.stream().anyMatch(String::isEmpty))throw unsupported();return items;
    }
    private static SQLException unsupported(){return new SQLException("Experimental native query saving supports one local table SELECT, DISTINCT, AS aliases, WHERE, GROUP BY, HAVING and ORDER BY. Joins, UNION, TOP, PARAMETERS and action queries are unavailable.");}
    private static void add(List<QueryImpl.Row> rows,int objectId,byte attribute,String expression,String name1,String name2,Short flag) {
        rows.add(new QueryImpl.Row(null,attribute,expression,flag,null,name1,name2,objectId,java.nio.ByteBuffer.allocate(4).putInt(rows.size()).array()));
    }
    static String write(Database db,String name,String sql)throws Exception {
        Table objects=db.getSystemTable("MSysObjects");Set<Integer> ids=new HashSet<>();Object owner=null,parent=null,prototypeId=null;
        int id=-1000000000;for(Row row:objects)if(row.get("Id") instanceof Number number)ids.add(number.intValue());
        while(ids.contains(id)){if(id==-1)throw new SQLException("No safe query object ID available.");id++;}
        Definition definition=parse(name,sql,id);if(db.getTableNames().stream().noneMatch(t->t.equalsIgnoreCase(definition.table())))throw new SQLException("Native save requires one existing local table.");
        for(Row row:objects)if(definition.table().equalsIgnoreCase(Objects.toString(row.get("Name"),""))&&row.get("Type") instanceof Number number&&number.intValue()==1){owner=row.get("Owner");parent=row.get("ParentId");prototypeId=row.get("Id");break;}
        if(parent==null)throw new SQLException("Cannot safely identify native query catalog parent.");
        Map<String,Object> catalog=new HashMap<>();catalog.put("Id",id);catalog.put("ParentId",parent);catalog.put("Owner",owner);catalog.put("Name",name);catalog.put("Type",(short)5);catalog.put("Flags",SELECT_QUERY_OBJECT_FLAG);catalog.put("DateCreate",java.time.LocalDateTime.now());catalog.put("DateUpdate",java.time.LocalDateTime.now());
        objects.addRowFromMap(catalog);Table queries=db.getSystemTable("MSysQueries");for(QueryImpl.Row row:definition.rows())queries.addRowFromMap(row.toTableRow());
        Table aces=db.getSystemTable("MSysACEs");List<Map<String,Object>> permissions=new ArrayList<>();
        for(Row row:aces)if(Objects.equals(prototypeId,row.get("ObjectId"))){Map<String,Object> permission=new HashMap<>(row);permission.put("ObjectId",id);permissions.add(permission);}
        for(Map<String,Object> permission:permissions)aces.addRowFromMap(permission);
        db.flush();return definition.rendered();
    }
}
