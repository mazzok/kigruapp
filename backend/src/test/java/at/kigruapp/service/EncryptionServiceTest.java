package at.kigruapp.service;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain unit test (no CDI, no Mongo) — sets the key field directly.
 */
class EncryptionServiceTest {

    private static final String VALID_KEY_32 =
            Base64.getEncoder().encodeToString(new byte[32]); // 32 zero bytes → valid length

    private EncryptionService withKey(String key) {
        EncryptionService svc = new EncryptionService();
        svc.encryptionKey = Optional.ofNullable(key);
        return svc;
    }

    @Test
    void roundTrip_returnsOriginal() {
        EncryptionService svc = withKey(VALID_KEY_32);
        String plain = "s3cr3t-smtp-pw";
        String blob = svc.encrypt(plain);
        assertNotEquals(plain, blob, "blob must not be the plaintext");
        assertEquals(plain, svc.decrypt(blob));
    }

    @Test
    void encrypt_usesFreshNonce_soCiphertextsDiffer() {
        EncryptionService svc = withKey(VALID_KEY_32);
        String plain = "same-input";
        assertNotEquals(svc.encrypt(plain), svc.encrypt(plain),
                "two encryptions of the same plaintext must differ (fresh nonce)");
    }

    @Test
    void isConfigured_falseWhenKeyAbsent() {
        assertFalse(withKey(null).isConfigured());
    }

    @Test
    void isConfigured_falseWhenKeyWrongLength() {
        String key16 = Base64.getEncoder().encodeToString(new byte[16]);
        assertFalse(withKey(key16).isConfigured(), "16-byte key must be rejected");
    }

    @Test
    void isConfigured_trueForValid32ByteKey() {
        assertTrue(withKey(VALID_KEY_32).isConfigured());
    }

    @Test
    void decrypt_withWrongKey_throws() {
        String blob = withKey(VALID_KEY_32).encrypt("data");
        String otherKey = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes());
        EncryptionService other = withKey(otherKey);
        assertThrows(RuntimeException.class, () -> other.decrypt(blob));
    }
}
