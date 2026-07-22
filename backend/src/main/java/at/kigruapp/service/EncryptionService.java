package at.kigruapp.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

/**
 * Encrypts/decrypts secrets (SMTP password) at rest with AES-256-GCM.
 * The key is provided as a base64-encoded 32-byte value via configuration
 * (env {@code KIGRUAPP_MAIL_ENCRYPTION_KEY}); there is no default. When the key
 * is absent or malformed the service reports {@link #isConfigured()} == false and
 * the mail feature is fail-closed.
 */
@ApplicationScoped
public class EncryptionService {

    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();

    @ConfigProperty(name = "kigruapp.mail.encryption-key")
    Optional<String> encryptionKey;

    /** @return true when a syntactically valid 32-byte base64 key is configured. */
    public boolean isConfigured() {
        return decodeKey() != null;
    }

    /** @return base64(iv || ciphertext||tag). */
    public String encrypt(String plain) {
        byte[] key = requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String blob) {
        byte[] key = requireKey();
        try {
            byte[] all = Base64.getDecoder().decode(blob);
            byte[] iv = Arrays.copyOfRange(all, 0, IV_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(all, IV_BYTES, all.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }

    private byte[] requireKey() {
        byte[] key = decodeKey();
        if (key == null) {
            throw new IllegalStateException("Mail encryption key is not configured");
        }
        return key;
    }

    /** @return the 32-byte key, or null if absent/malformed/wrong length. */
    private byte[] decodeKey() {
        if (encryptionKey == null || encryptionKey.isEmpty()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encryptionKey.get().trim());
            return decoded.length == KEY_BYTES ? decoded : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
