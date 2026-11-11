package cn.edu.whu.irlab.systemdirectionclassifier.dao;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/** Parameterized JDBC for identity and knowledge aggregates. */
@Repository
@RequiredArgsConstructor
public class IdentityDAO {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private static final Set<String> TABLES = new HashSet<>(Arrays.asList(
        "demo_user","demo_role","demo_permission","demo_menu","demo_user_role",
        "demo_role_permission","demo_role_menu","demo_team","demo_team_member",
        "demo_discipline","demo_knowledge_base","demo_knowledge_version","demo_knowledge_discipline",
        "demo_knowledge_summary","demo_knowledge_cluster","demo_knowledge_cluster_member",
        "demo_knowledge_seed_paper","demo_seed_paper_discipline","demo_knowledge_asset",
        "demo_file_asset","demo_asset_reference","demo_knowledge_import","demo_knowledge_import_item",
        "demo_model_connection","demo_model_connection_revision","demo_model_credential","demo_model_connection_test"));
    private String table(String name) {
        if (!TABLES.contains(name)) throw new IllegalArgumentException("Unknown business table");
        return name;
    }
    private String column(String name) {
        if (!name.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Invalid column");
        return name;
    }
    public List<Map<String,Object>> query(String sql, Object... args) {
        return jdbc.query(sql, (rs,n)->{
            Map<String,Object> row=new LinkedHashMap<>();
            ResultSetMetaData meta=rs.getMetaData();
            for(int i=1;i<=meta.getColumnCount();i++) {
                String key=meta.getColumnLabel(i);
                StringBuilder camel=new StringBuilder(); boolean upper=false;
                for(char c:key.toCharArray()) {
                    if(c=='_') upper=true;
                    else { camel.append(upper?Character.toUpperCase(c):c); upper=false; }
                }
                Object value=rs.getObject(i);
                if(value instanceof Timestamp) value=((Timestamp)value).toInstant().toString();
                if("preferences".equals(key) && value!=null) {
                    try { value=json.readTree(value.toString()); }
                    catch(Exception e) { throw new SQLException("Invalid stored preferences JSON",e); }
                }
                row.put(camel.toString(),value);
            }
            return row;
        },args);
    }
    public Map<String,Object> one(String sql,Object...args) {
        List<Map<String,Object>> rows=query(sql,args);
        return rows.isEmpty()?null:rows.get(0);
    }
    public Map<String,Object> find(String name,Long id,boolean lock) {
        return one("SELECT * FROM "+table(name)+" WHERE id=?"+(lock?" FOR UPDATE":""),id);
    }
    public long count(String sql,Object...args) {
        Long count=jdbc.queryForObject(sql,Long.class,args);
        return count==null?0:count;
    }
    public int execute(String sql,Object...args) { return jdbc.update(sql,args); }
    public long insert(String name,Map<String,Object> data) {
        List<String> keys=new ArrayList<>(data.keySet());
        String columns=keys.stream().map(this::column).collect(Collectors.joining(","));
        String placeholders=String.join(",",Collections.nCopies(keys.size(),"?"));
        KeyHolder holder=new GeneratedKeyHolder();
        jdbc.update(c->{
            PreparedStatement ps=c.prepareStatement("INSERT INTO "+table(name)+" ("+columns+") VALUES ("+placeholders+")",Statement.RETURN_GENERATED_KEYS);
            for(int i=0;i<keys.size();i++) ps.setObject(i+1,data.get(keys.get(i)));
            return ps;
        },holder);
        Number key=holder.getKey();
        return key==null?0:key.longValue();
    }
    public void update(String name,Long id,Map<String,Object> data) {
        if(data.isEmpty()) return;
        List<String> keys=new ArrayList<>(data.keySet());
        String set=keys.stream().map(k->column(k)+"=?").collect(Collectors.joining(","));
        List<Object> args=keys.stream().map(data::get).collect(Collectors.toList());args.add(id);
        jdbc.update("UPDATE "+table(name)+" SET "+set+" WHERE id=?",args.toArray());
    }
}

