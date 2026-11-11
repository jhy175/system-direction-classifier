package cn.edu.whu.irlab.systemdirectionclassifier.manager.model;

import cn.edu.whu.irlab.systemdirectionclassifier.api.identity.IdentityException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Arrays;

@Component
@Profile("mysql")
class ModelCredentialCipher {
    static final String KEY_ID = "local-aes-gcm-v1";
    private final Path keyPath;
    private final SecureRandom random = new SecureRandom();

    ModelCredentialCipher(@Value("${classifier.model.credential-key-file:.local/model-secrets/local-aes-gcm-v1.bin}") String path) {
        keyPath = Paths.get(path);
    }

    byte[] encrypt(String database, long connection, byte[] scope, long version, String secret) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
            try {
                byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, nonce, database, connection, scope, version).doFinal(raw);
                byte[] envelope = Arrays.copyOf(nonce, nonce.length + encrypted.length);
                System.arraycopy(encrypted, 0, envelope, nonce.length, encrypted.length);
                return envelope;
            } finally {
                Arrays.fill(raw, (byte)0);
            }
        } catch (Exception e) {
            throw new IdentityException(503, "模型凭据暂时无法加密，请检查本机主密钥配置");
        }
    }

    String decrypt(String database, long connection, byte[] scope, long version, String keyId, byte[] envelope) {
        try {
            if (!KEY_ID.equals(keyId) || envelope == null || envelope.length <= 28) throw new IllegalArgumentException();
            byte[] raw = cipher(Cipher.DECRYPT_MODE, Arrays.copyOf(envelope, 12), database, connection, scope, version)
                .doFinal(envelope, 12, envelope.length - 12);
            try {
                return new String(raw, StandardCharsets.UTF_8);
            } finally {
                Arrays.fill(raw, (byte)0);
            }
        } catch (Exception e) {
            throw new IdentityException(503, "模型凭据暂时无法解密，请检查本机主密钥配置");
        }
    }

    private Cipher cipher(int mode, byte[] nonce, String database, long connection, byte[] scope, long version) throws Exception {
        byte[] key = Files.readAllBytes(keyPath);
        try {
            if (key.length != 32) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            StringBuilder hex = new StringBuilder(scope.length * 2);
            for (byte b : scope) {
                hex.append(Character.forDigit((b & 255) >>> 4, 16)).append(Character.forDigit(b & 15, 16));
            }
            String aad = KEY_ID + ":" + database + ":" + connection + ":" + hex + ":" + version;
            cipher.updateAAD(aad.getBytes(StandardCharsets.US_ASCII));
            return cipher;
        } finally {
            Arrays.fill(key, (byte)0);
        }
    }
}
