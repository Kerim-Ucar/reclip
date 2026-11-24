package com.kerim.reclip.config;

import com.kerim.reclip.platform.macos.MacOsPermissions;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * AES-256-GCM encryption helper for local settings and persisted clipboard history.
 * On macOS, the 256-bit key is stored in the user's Login Keychain; on other platforms
 * (or if Keychain is unavailable), it falls back to an owner-only ({@code 0600}) key file.
 */
public final class LocalEncryption {

    private static final String HEADER = "RECLIP_ENC_V1:";
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int KEY_BITS = 256;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String KEYCHAIN_SERVICE = "com.kerim.reclip.encryption-key";
    private static final String KEYCHAIN_ACCOUNT = "reclip";

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    private LocalEncryption(SecretKey secretKey) {
        this.secretKey = secretKey;
    }

    /**
     * Returns a no-op encryptor that stores content as plain UTF-8 (used in isolated unit tests).
     */
    public static LocalEncryption disabled() {
        return new LocalEncryption(null);
    }

    /**
     * Creates an encryptor backed by the given 256-bit AES key.
     */
    public static LocalEncryption withKey(SecretKey secretKey) {
        Objects.requireNonNull(secretKey, "secretKey must not be null");
        return new LocalEncryption(secretKey);
    }

    /**
     * Loads or generates the local encryption key using the macOS Keychain (with
     * owner-only file fallback).
     */
    public static LocalEncryption defaultEncryptor() {
        SecretKey key = loadOrCreateDefaultKey();
        return new LocalEncryption(key);
    }

    public boolean isEnabled() {
        return secretKey != null;
    }

    /**
     * Encrypts UTF-8 plaintext if encryption is enabled, otherwise returns the plaintext bytes.
     */
    public byte[] encrypt(String plaintext) {
        Objects.requireNonNull(plaintext, "plaintext must not be null");
        byte[] plainBytes = plaintext.getBytes(StandardCharsets.UTF_8);
        if (secretKey == null) {
            return plainBytes;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plainBytes);
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            String payload = HEADER + Base64.getEncoder().encodeToString(combined);
            return payload.getBytes(StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt local Reclip data", e);
        }
    }

    /**
     * Decrypts bytes written by {@link #encrypt(String)}, transparently reading legacy
     * unencrypted UTF-8 content when the {@value #HEADER} prefix is absent.
     */
    public String decrypt(byte[] data) {
        Objects.requireNonNull(data, "data must not be null");
        String raw = new String(data, StandardCharsets.UTF_8);
        if (!raw.startsWith(HEADER)) {
            return raw;
        }
        if (secretKey == null) {
            throw new IllegalStateException("Encrypted data cannot be read without an encryption key");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(raw.substring(HEADER.length()).trim());
            if (combined.length <= IV_BYTES) {
                throw new IllegalStateException("Corrupt encrypted payload");
            }
            byte[] iv = new byte[IV_BYTES];
            byte[] ciphertext = new byte[combined.length - IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_BYTES);
            System.arraycopy(combined, IV_BYTES, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plainBytes = cipher.doFinal(ciphertext);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Failed to decrypt local Reclip data", e);
        }
    }

    /**
     * Generates a fresh 256-bit AES key.
     */
    public static SecretKey generateAesKey() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(KEY_BITS);
            return generator.generateKey();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("AES key generation unavailable", e);
        }
    }

    private static SecretKey loadOrCreateDefaultKey() {
        if (MacOsPermissions.isMacOs()) {
            Optional<SecretKey> keychainKey = readFromMacOsKeychain();
            if (keychainKey.isPresent()) {
                return keychainKey.get();
            }
        }
        Path fallbackKeyPath = Path.of(System.getProperty("user.home"), ".reclip", ".key");
        Optional<SecretKey> fileKey = readFromKeyFile(fallbackKeyPath);
        if (fileKey.isPresent()) {
            return fileKey.get();
        }
        SecretKey generated = generateAesKey();
        String encoded = Base64.getEncoder().encodeToString(generated.getEncoded());
        if (MacOsPermissions.isMacOs() && writeToMacOsKeychain(encoded)) {
            return generated;
        }
        writeToKeyFile(fallbackKeyPath, encoded);
        return generated;
    }

    private static Optional<SecretKey> readFromMacOsKeychain() {
        try {
            Process process = new ProcessBuilder(
                    "security", "find-generic-password",
                    "-s", KEYCHAIN_SERVICE,
                    "-a", KEYCHAIN_ACCOUNT,
                    "-w")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.waitFor() == 0 && !output.isEmpty()) {
                byte[] keyBytes = Base64.getDecoder().decode(output);
                if (keyBytes.length == KEY_BITS / 8) {
                    return Optional.of(new SecretKeySpec(keyBytes, "AES"));
                }
            }
        } catch (Exception ignored) {
            // Fall back to key file if Keychain is unavailable.
        }
        return Optional.empty();
    }

    private static boolean writeToMacOsKeychain(String base64Key) {
        try {
            Process process = new ProcessBuilder(
                    "security", "add-generic-password",
                    "-U",
                    "-s", KEYCHAIN_SERVICE,
                    "-a", KEYCHAIN_ACCOUNT,
                    "-w", base64Key)
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Optional<SecretKey> readFromKeyFile(Path path) {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            String encoded = Files.readString(path, StandardCharsets.UTF_8).trim();
            byte[] keyBytes = Base64.getDecoder().decode(encoded);
            if (keyBytes.length == KEY_BITS / 8) {
                return Optional.of(new SecretKeySpec(keyBytes, "AES"));
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // Ignore unreadable/corrupt key file and regenerate.
        }
        return Optional.empty();
    }

    private static void writeToKeyFile(Path path, String encodedKey) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, encodedKey, StandardCharsets.UTF_8);
            setOwnerOnlyPermissions(path);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to store local encryption key at " + path, e);
        }
    }

    public static void setOwnerOnlyPermissions(Path path) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystem (e.g. Windows).
        }
    }
}
