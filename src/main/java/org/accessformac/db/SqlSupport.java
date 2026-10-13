package org.accessformac.db;

import java.sql.*;
import java.util.*;
import java.util.regex.*;

/** Small SQL lexer for parameter binding and native make-table translation, never a SQL engine. */
final class SqlSupport {
    record Token(String value, int start, int end, boolean identifier, boolean literal) {}
    record Plan(String sql, List<AccessDatabase.ParameterInfo> parameters, List<Integer> bindings) {}
    static List<Token> lex(String sql) throws SQLException {
        List<Token> result=new ArrayList<>();
        for(int i=0;i<sql.length();) {
            char c=sql.charAt(i); if(Character.isWhitespace(c)){i++;continue;}
            int start=i++;
            if(c=='\'' || c=='"' || c=='[' || c=='#') {
                char end=c=='['?']':c; boolean complete=false;
                while(i<sql.length()) {
                    if(sql.charAt(i++)==end) {
                        if(i<sql.length() && sql.charAt(i)==end && end!='#'){i++;continue;}
                        complete=true;break;
                    }
                }
                if(!complete)throw new SQLException("Unclosed SQL literal or identifier.");
                String value=c=='['?sql.substring(start+1,i-1).replace("]]","]"):sql.substring(start,i);
                result.add(new Token(value,start,i,c=='[',c!='['));
            } else if(Character.isLetterOrDigit(c)||c=='_') {
                while(i<sql.length()&&(Character.isLetterOrDigit(sql.charAt(i))||sql.charAt(i)=='_'))i++;
                result.add(new Token(sql.substring(start,i),start,i,Character.isLetter(c)||c=='_',false));
            } else result.add(new Token(Character.toString(c),start,i,false,false));
        }
        return result;
    }
    static Plan plan(String input) throws SQLException {
        if(input==null||input.isBlank())throw new SQLException("Enter an Access SQL statement.");
        String body=input.strip(); List<AccessDatabase.ParameterInfo> parameters=new ArrayList<>();
        List<Token> tokens=lex(body);
        if(tokens.get(0).value().equalsIgnoreCase("PARAMETERS")) {
            int separator=-1;
            for(Token token:tokens)if(token.value().equals(";")){separator=token.start();break;}
            if(separator<0)throw new SQLException("Finish PARAMETERS declarations with a semicolon.");
            String declarations=body.substring(tokens.get(0).end(),separator);
            List<Token> declarationTokens=lex(declarations); int start=0,depth=0;
            List<String> parts=new ArrayList<>();
            for(Token token:declarationTokens) {
                if(token.value().equals("("))depth++;
                if(token.value().equals(")"))depth--;
                if(token.value().equals(",")&&depth==0){parts.add(declarations.substring(start,token.start()));start=token.end();}
            }
            parts.add(declarations.substring(start)); Set<String> names=new HashSet<>();
            for(String part:parts) {
                List<Token> decl=lex(part); if(decl.size()<2||!decl.get(0).identifier())throw new SQLException("Expected parameter name and Access type: "+part);
                String name=decl.get(0).value(); String type=part.substring(decl.get(0).end()).strip(); sqlType(type);
                if(!names.add(name.toUpperCase(Locale.ROOT)))throw new SQLException("Duplicate parameter: "+name);
                parameters.add(new AccessDatabase.ParameterInfo(name,type));
            }
            body=body.substring(separator+1).strip(); tokens=lex(body);
        }
        List<Integer> bindings=new ArrayList<>(); StringBuilder prepared=new StringBuilder();int position=0;
        for(int i=0;i<tokens.size();i++) {
            Token token=tokens.get(i);int bind=-1;
            if(token.value().equals("?")&&!token.literal()) {
                if(!parameters.isEmpty())throw new SQLException("Use named parameters or ? placeholders, not both.");
                bind=bindings.size();
            } else if(token.identifier() && (i==0 || !tokens.get(i-1).value().equals("."))
                && (i+1==tokens.size() || !tokens.get(i+1).value().equals("."))) {
                for(int p=0;p<parameters.size();p++)if(token.value().equalsIgnoreCase(parameters.get(p).name())){bind=p;break;}
            }
            if(bind>=0){prepared.append(body,position,token.start()).append('?');position=token.end();bindings.add(bind);}
        }
        prepared.append(body.substring(position));
        if(parameters.isEmpty())for(int p=0;p<bindings.size();p++)parameters.add(new AccessDatabase.ParameterInfo("Parameter "+(p+1),"VALUE"));
        for(int p=0;p<parameters.size();p++)if(!bindings.contains(p))throw new SQLException("Declared parameter is unused: "+parameters.get(p).name());
        return new Plan(prepared.toString(),List.copyOf(parameters),List.copyOf(bindings));
    }
    static int sqlType(String type)throws SQLException {
        String value=type.toUpperCase(Locale.ROOT).replaceAll("\\s*\\([^)]*\\)","").strip();
        return switch(value) {
            case "VALUE" -> Types.JAVA_OBJECT;
            case "TEXT","CHAR","VARCHAR","LONGTEXT","MEMO" -> Types.VARCHAR;
            case "LONG","INTEGER","INT","COUNTER" -> Types.INTEGER;
            case "SHORT","SMALLINT","BYTE" -> Types.SMALLINT;
            case "DOUBLE","SINGLE","FLOAT","REAL" -> Types.DOUBLE;
            case "CURRENCY","MONEY","DECIMAL","NUMERIC" -> Types.DECIMAL;
            case "DATETIME","DATE","TIMESTAMP" -> Types.TIMESTAMP;
            case "BIT","BOOLEAN","YESNO" -> Types.BOOLEAN;
            default -> throw new SQLException("Unsupported parameter type: "+type);
        };
    }
    static Object coerce(Object value,int type)throws SQLException {
        if(value==null)return null;
        try {
            return switch(type) {
                case Types.VARCHAR -> value.toString();
                case Types.INTEGER -> new java.math.BigDecimal(value.toString()).intValueExact();
                case Types.SMALLINT -> new java.math.BigDecimal(value.toString()).shortValueExact();
                case Types.BIGINT -> new java.math.BigDecimal(value.toString()).longValueExact();
                case Types.DOUBLE -> finiteDouble(value);
                case Types.DECIMAL -> value instanceof java.math.BigDecimal?value:new java.math.BigDecimal(value.toString());
                case Types.BOOLEAN -> value instanceof Boolean?value:parseBoolean(value.toString());
                case Types.TIMESTAMP -> value instanceof java.sql.Timestamp?value:value instanceof java.time.LocalDateTime d?Timestamp.valueOf(d):Timestamp.valueOf(value.toString().length()==10?value+" 00:00:00":value.toString().replace('T',' '));
                default -> value;
            };
        }catch(RuntimeException invalid){throw new SQLException("Value '"+value+"' does not match the requested field/parameter type.",invalid);}
    }
    private static double finiteDouble(Object value) throws SQLException {
        double number=value instanceof Number n?n.doubleValue():Double.parseDouble(value.toString());
        if(!Double.isFinite(number))throw new SQLException("Access numeric values must be finite.");
        return number;
    }
    private static boolean parseBoolean(String value)throws SQLException {
        if(Set.of("true","yes","1","-1").contains(value.toLowerCase(Locale.ROOT)))return true;
        if(Set.of("false","no","0").contains(value.toLowerCase(Locale.ROOT)))return false;
        throw new SQLException("Expected yes/no or true/false.");
    }
    static String makeTable(String sql)throws SQLException {
        List<Token> tokens=lex(sql); if(tokens.isEmpty()||!tokens.get(0).value().equalsIgnoreCase("SELECT"))return sql;
        int depth=0,into=-1,from=-1;
        for(int i=1;i<tokens.size();i++) {
            Token t=tokens.get(i); if(t.value().equals("("))depth++;if(t.value().equals(")"))depth--;
            if(depth==0&&!t.literal()&&sql.charAt(t.start())!='['&&t.value().equalsIgnoreCase("INTO")){if(into>=0)throw new SQLException("Ambiguous SELECT INTO.");into=i;}
            if(depth==0&&!t.literal()&&sql.charAt(t.start())!='['&&t.value().equalsIgnoreCase("FROM")&&from<0)from=i;
        }
        if(into<0)return sql;
        if(from<0||from!=into+2||!tokens.get(into+1).identifier())
            throw new SQLException("SELECT INTO currently requires one local destination table before FROM.");
        Token destination=tokens.get(into+1);
        String name=destination.value(); if(name.contains("]"))throw new SQLException("Destination names containing ] are unavailable.");
        String select=sql.substring(0,tokens.get(into).start())+sql.substring(tokens.get(from).start());
        select=select.strip(); if(select.endsWith(";"))select=select.substring(0,select.length()-1);
        return "CREATE TABLE ["+name+"] AS ("+select+") WITH DATA";
    }
}
