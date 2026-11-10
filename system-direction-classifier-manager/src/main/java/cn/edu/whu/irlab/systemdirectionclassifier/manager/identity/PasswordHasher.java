package cn.edu.whu.irlab.systemdirectionclassifier.manager.identity;
import cn.edu.whu.irlab.systemdirectionclassifier.api.identity.IdentityException;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.*;
import java.util.*;
public final class PasswordHasher {
    private static final int ITERATIONS=600000;
    private static final SecureRandom RANDOM=new SecureRandom();
    private PasswordHasher() {}
    public static void validate(String password) {
        if(password==null || password.length()<10 || password.length()>128)
            throw new IdentityException(400,"密码长度应为10至128个字符");
    }
    public static String hash(String password) {
        validate(password);
        byte[] salt=new byte[16];RANDOM.nextBytes(salt);
        return "pbkdf2-sha256$"+ITERATIONS+"$"+Base64.getEncoder().encodeToString(salt)+"$"+
            Base64.getEncoder().encodeToString(derive(password,salt,ITERATIONS));
    }
    public static boolean matches(String password,String stored) {
        if(password==null || password.length()>128 || stored==null) return false;
        try {
            String[] parts=stored.split("\\$");
            if(parts.length!=4 || !"pbkdf2-sha256".equals(parts[0])) return false;
            int iterations=Integer.parseInt(parts[1]);
            if(iterations<100000 || iterations>1000000) return false;
            return MessageDigest.isEqual(Base64.getDecoder().decode(parts[3]),
                derive(password,Base64.getDecoder().decode(parts[2]),iterations));
        } catch(RuntimeException e) { return false; }
    }
    private static byte[] derive(String password,byte[] salt,int iterations) {
        PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),salt,iterations,256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch(GeneralSecurityException e) { throw new IllegalStateException("Password hashing unavailable",e); }
        finally { spec.clearPassword(); }
    }
    public static String stamp(String hash) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(
                (hash==null?"":hash).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch(GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
}

