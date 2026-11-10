import java.nio.file.*;
import java.io.*;
import java.sql.*;
import java.util.*;
/** Checks the actual MySQL schema, migration history and stored credential format. */
public final class UsersDatabaseCheck {
    public static void main(String[] args) throws Exception {
        Properties p=new Properties();
        p.load(new StringReader(new String(Files.readAllBytes(Paths.get(args[0])),java.nio.charset.StandardCharsets.UTF_8).replace("\uFEFF","")));
        String database=args.length>1?args[1]:"direction_classifier_test";
        if(!Arrays.asList("direction_classifier","direction_classifier_test").contains(database))throw new IllegalArgumentException("Unexpected database");
        Map<String,Set<String>> required=new LinkedHashMap<>();
        required.put("demo_user",new HashSet<>(Arrays.asList("id","login")));
        required.put("demo_role",new HashSet<>(Arrays.asList("id","code","name")));
        required.put("demo_permission",new HashSet<>(Arrays.asList("id","code","name")));
        required.put("demo_menu",new HashSet<>(Arrays.asList("id","name")));
        required.put("demo_user_role",new HashSet<>(Arrays.asList("user_id","role_id")));
        required.put("demo_role_permission",new HashSet<>(Arrays.asList("role_id","permission_id")));
        required.put("demo_role_menu",new HashSet<>(Arrays.asList("role_id","menu_id")));
        required.put("demo_team",new HashSet<>(Arrays.asList("id","name","owner_user_id")));
        required.put("demo_team_member",new HashSet<>(Arrays.asList("team_id","user_id")));
        int columns=0,hashes=0;
        try(Connection c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:"+p.getProperty("mysqlPort")+"/"+database+"?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC","classifier_app",p.getProperty("dbPassword"))) {
            for(Map.Entry<String,Set<String>> entry:required.entrySet()) {
                int found=0;
                try(PreparedStatement s=c.prepareStatement("SELECT column_name,is_nullable FROM information_schema.columns WHERE table_schema=? AND table_name=?")) {
                    s.setString(1,database);s.setString(2,entry.getKey());
                    try(ResultSet r=s.executeQuery()) {
                        while(r.next()) {
                            found++;columns++;
                            if(entry.getValue().contains(r.getString(1)) != "NO".equals(r.getString(2)))throw new AssertionError("Wrong NULL constraint: "+entry.getKey()+"."+r.getString(1));
                        }
                    }
                }
                if(found==0)throw new AssertionError("Missing table: "+entry.getKey());
            }
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT password_hash FROM demo_user WHERE password_hash IS NOT NULL")) {
                while(r.next()){if(!r.getString(1).startsWith("pbkdf2-sha256$600000$"))throw new AssertionError("Unexpected credential format");hashes++;}
            }
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM DATABASECHANGELOG WHERE ID='users-2.0.0-schema' AND EXECTYPE='EXECUTED'")) {
                r.next();if(r.getInt(1)!=1)throw new AssertionError("Schema migration not applied exactly once");
            }
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM demo_team t LEFT JOIN demo_team_member m ON m.team_id=t.id AND m.user_id=t.owner_user_id WHERE m.user_id IS NULL OR m.status<>1 OR m.status IS NULL OR m.member_role IS NULL OR m.member_role NOT IN ('member','admin')")) {
                r.next();if(r.getInt(1)!=0)throw new AssertionError("Invalid team owner membership");
            }
        }
        System.out.println("PASS "+database+": 9 tables, "+columns+" NULL constraints, "+hashes+" password hashes, migration history and owner memberships.");
    }
}
