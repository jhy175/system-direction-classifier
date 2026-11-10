package cn.edu.whu.irlab.systemdirectionclassifier.manager.identity;
import cn.edu.whu.irlab.systemdirectionclassifier.api.identity.IdentityException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PasswordHasherTest {
    @Test void saltedPasswordsAndVerification() {
        String first=PasswordHasher.hash("correct-local-password");
        String second=PasswordHasher.hash("correct-local-password");
        assertNotEquals(first,second);
        assertTrue(PasswordHasher.matches("correct-local-password",first));
        assertFalse(PasswordHasher.matches("incorrect-password",first));
        assertNotEquals(PasswordHasher.stamp(first),PasswordHasher.stamp(second));
    }
    @Test void missingAndMalformedHashesNeverAuthenticate() {
        assertFalse(PasswordHasher.matches("anything",null));
        assertFalse(PasswordHasher.matches(null,"invalid"));
        assertFalse(PasswordHasher.matches("anything","plaintext"));
        assertFalse(PasswordHasher.matches("anything","pbkdf2-sha256$999999999$invalid$invalid"));
    }
    @Test void invalidNewPasswordLengthsRejected() {
        assertThrows(IdentityException.class,()->PasswordHasher.hash("short"));
        assertThrows(IdentityException.class,()->PasswordHasher.hash(null));
        assertThrows(IdentityException.class,()->PasswordHasher.hash(new String(new char[129])));
    }
}
