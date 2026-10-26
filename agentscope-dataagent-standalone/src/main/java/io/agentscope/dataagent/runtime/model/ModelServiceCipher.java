package io.agentscope.dataagent.runtime.model;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Encrypts configuration with an independent key kept outside the database. */
@Component
public class ModelServiceCipher {
    private static final byte[] AAD =
            "dataagent:model-services:v1".getBytes(StandardCharsets.UTF_8);
    private final String suppliedKey;
    private final Path keyFile;
    private final SecureRandom random = new SecureRandom();

    public ModelServiceCipher(
            @Value("${dataagent.model-services.encryption-key:}") String suppliedKey,
            @Value("${user.home}") String home) {
        this.suppliedKey = suppliedKey;
        this.keyFile = Path.of(home, ".agentscope-dataagent", "model-services.key");
    }

    public String encrypt(String text) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(AAD);
            return "v1."
                    + Base64.getEncoder().encodeToString(nonce)
                    + "."
                    + Base64.getEncoder()
                            .encodeToString(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("模型配置加密失败，请检查独立主密钥或持久目录权限。");
        }
    }

    public String decrypt(String text) {
        try {
            String[] parts = text.split("\\.", -1);
            if (parts.length != 3 || !parts[0].equals("v1")) throw new IllegalArgumentException();
            byte[] nonce = Base64.getDecoder().decode(parts[1]);
            if (nonce.length != 12) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(AAD);
            return new String(
                    cipher.doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("模型配置无法解密，请恢复与数据库匹配的独立主密钥；未切换其他服务。");
        }
    }

    private synchronized SecretKeySpec key(boolean create) throws Exception {
        String encoded = suppliedKey;
        if (encoded == null || encoded.isBlank()) {
            if (Files.isSymbolicLink(keyFile)) throw new IllegalStateException();
            if (!Files.exists(keyFile, LinkOption.NOFOLLOW_LINKS)) {
                if (!create) throw new IllegalStateException();
                Files.createDirectories(keyFile.getParent());
                byte[] bytes = new byte[32];
                random.nextBytes(bytes);
                String generated = Base64.getEncoder().encodeToString(bytes);
                try {
                    if (Files.getFileStore(keyFile.getParent())
                            .supportsFileAttributeView("posix")) {
                        Files.createFile(
                                keyFile,
                                PosixFilePermissions.asFileAttribute(
                                        PosixFilePermissions.fromString("rw-------")));
                    } else {
                        Files.createFile(keyFile);
                    }
                    Files.writeString(keyFile, generated, StandardCharsets.UTF_8);
                } catch (FileAlreadyExistsException ignored) {
                    // Another initializer already supplied the durable key.
                }
            }
            encoded = Files.readString(keyFile, StandardCharsets.UTF_8).trim();
        }
        byte[] bytes = Base64.getDecoder().decode(encoded.trim());
        if (bytes.length != 32) throw new IllegalArgumentException();
        return new SecretKeySpec(bytes, "AES");
    }
}
