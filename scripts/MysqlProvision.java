import java.nio.file.*;
import java.io.*;
import java.sql.*;
import java.util.Properties;

/** Local provisioning only. Credentials are read from an ignored file, never command arguments. */
public final class MysqlProvision {
    public static void main(String[] args) throws Exception {
        Properties p = new Properties();
        String settings=new String(Files.readAllBytes(Paths.get(args[0])),java.nio.charset.StandardCharsets.UTF_8);
        p.load(new StringReader(settings.replace("\uFEFF","")));
        Class.forName("com.mysql.cj.jdbc.Driver");
        String url = "jdbc:mysql://127.0.0.1:" + p.getProperty("mysqlPort")
            + "/?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC";
        String rootPassword = p.getProperty("rootPassword");
        boolean fresh=args.length>1 && "fresh".equals(args[1]);
        Connection connection;
        try { connection=DriverManager.getConnection(url,"root",rootPassword); }
        catch(SQLException error) {
            if(!fresh)throw error;
            connection=DriverManager.getConnection(url,"root","");
        }
        try (Connection c = connection; Statement s = c.createStatement()) {
            try(ResultSet result=s.executeQuery("SELECT @@server_uuid")) {
                result.next();
                Path autoFile=Paths.get(args[0]).toAbsolutePath().getParent().getParent().resolve(".tools/mysql/data/auto.cnf");
                Properties auto=new Properties();
                try(Reader reader=Files.newBufferedReader(autoFile,java.nio.charset.StandardCharsets.UTF_8)) { auto.load(reader); }
                if(!result.getString(1).equalsIgnoreCase(auto.getProperty("server-uuid","")))throw new IllegalStateException("Port belongs to another MySQL instance; no changes made");
            }
            if(args.length>1 && "shutdown".equals(args[1])) { s.execute("SHUTDOWN");return; }
            if (fresh) s.execute("ALTER USER 'root'@'localhost' IDENTIFIED BY '" + rootPassword + "'");
            s.execute("CREATE DATABASE IF NOT EXISTS direction_classifier CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            s.execute("CREATE DATABASE IF NOT EXISTS direction_classifier_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            for (String host : new String[]{"localhost", "127.0.0.1"}) {
                s.execute("CREATE USER IF NOT EXISTS 'classifier_app'@'" + host + "' IDENTIFIED BY '" + p.getProperty("dbPassword") + "'");
                s.execute("GRANT ALL PRIVILEGES ON direction_classifier.* TO 'classifier_app'@'" + host + "'");
                s.execute("GRANT ALL PRIVILEGES ON direction_classifier_test.* TO 'classifier_app'@'" + host + "'");
            }
        }
        System.out.println("MySQL ready: direction_classifier and direction_classifier_test; dedicated application account provisioned.");
    }
}
