package com.kerim.reclip.config;

import com.kerim.reclip.platform.macos.MacOsPermissions;
import com.kerim.reclip.platform.windows.WindowsWindowHelper;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * AES-256-GCM encryption helper for local settings and persisted clipboard history.
 * On macOS, the 256-bit key is stored in the user's Login Keychain; on Windows, it is
 * protected using Windows DPAPI ({@code CryptProtectData}) with an owner-only NTFS ACL;
 * on other platforms, it falls back to an owner-only ({@code 0600}) key file.
 */
public final class LocalEncryption {

    private static final String HEADER = "RECLIP_ENC_V1:";
    private static final String DPAPI_KEY_HEADER = "RECLIP_DPAPI_V1:";
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int KEY_BITS = 256;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int CRYPTPROTECT_UI_FORBIDDEN = 0x1;
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
        if (MacOsPermissions.isMacOs()) {
            String encoded = Base64.getEncoder().encodeToString(generated.getEncoded());
            if (writeToMacOsKeychain(encoded)) {
                return generated;
            }
        }
        writeKeyToFile(fallbackKeyPath, generated);
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
            String raw = Files.readString(path, StandardCharsets.UTF_8).trim();
            if (raw.startsWith(DPAPI_KEY_HEADER)) {
                if (!WindowsWindowHelper.isWindows()) {
                    return Optional.empty();
                }
                byte[] protectedBytes = Base64.getDecoder().decode(raw.substring(DPAPI_KEY_HEADER.length()).trim());
                Optional<byte[]> unprotected = unprotectWithWindowsDpapi(protectedBytes);
                if (unprotected.isPresent() && unprotected.get().length == KEY_BITS / 8) {
                    return Optional.of(new SecretKeySpec(unprotected.get(), "AES"));
                }
                return Optional.empty();
            }
            byte[] keyBytes = Base64.getDecoder().decode(raw);
            if (keyBytes.length == KEY_BITS / 8) {
                SecretKey key = new SecretKeySpec(keyBytes, "AES");
                if (WindowsWindowHelper.isWindows()) {
                    try {
                        writeKeyToFile(path, key);
                    } catch (RuntimeException ignored) {
                        // Best-effort upgrade to DPAPI storage.
                    }
                }
                return Optional.of(key);
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // Ignore unreadable/corrupt key file and regenerate.
        }
        return Optional.empty();
    }

    private static void writeKeyToFile(Path path, SecretKey key) {
        String serialized = Base64.getEncoder().encodeToString(key.getEncoded());
        if (WindowsWindowHelper.isWindows()) {
            Optional<byte[]> protectedBytes = protectWithWindowsDpapi(key.getEncoded());
            if (protectedBytes.isPresent()) {
                serialized = DPAPI_KEY_HEADER + Base64.getEncoder().encodeToString(protectedBytes.get());
            }
        }
        writeToKeyFile(path, serialized);
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

    static Optional<byte[]> protectWithWindowsDpapi(byte[] plainBytes) {
        if (!WindowsWindowHelper.isWindows() || plainBytes == null || plainBytes.length == 0) {
            return Optional.empty();
        }
        WindowsDpapi dpapi = WindowsDpapi.INSTANCE;
        if (dpapi == null) {
            return Optional.empty();
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment inData = arena.allocate(plainBytes.length);
            MemorySegment.copy(plainBytes, 0, inData, ValueLayout.JAVA_BYTE, 0, plainBytes.length);
            MemorySegment inBlob = allocateDataBlob(arena, plainBytes.length, inData);
            MemorySegment outBlob = arena.allocate(16L, 8L);

            int ok = (int) dpapi.cryptProtectData.invokeExact(
                    inBlob,
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                    CRYPTPROTECT_UI_FORBIDDEN,
                    outBlob);
            if (ok == 0) {
                return Optional.empty();
            }
            int outLen = outBlob.get(ValueLayout.JAVA_INT, 0L);
            MemorySegment outPtr = outBlob.get(ValueLayout.ADDRESS, 8L);
            try {
                if (outLen <= 0 || outPtr.equals(MemorySegment.NULL)) {
                    return Optional.empty();
                }
                return Optional.of(outPtr.reinterpret(outLen).toArray(ValueLayout.JAVA_BYTE));
            } finally {
                if (!outPtr.equals(MemorySegment.NULL)) {
                    MemorySegment ignored = (MemorySegment) dpapi.localFree.invokeExact(outPtr);
                }
            }
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    static Optional<byte[]> unprotectWithWindowsDpapi(byte[] protectedBytes) {
        if (!WindowsWindowHelper.isWindows() || protectedBytes == null || protectedBytes.length == 0) {
            return Optional.empty();
        }
        WindowsDpapi dpapi = WindowsDpapi.INSTANCE;
        if (dpapi == null) {
            return Optional.empty();
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment inData = arena.allocate(protectedBytes.length);
            MemorySegment.copy(protectedBytes, 0, inData, ValueLayout.JAVA_BYTE, 0, protectedBytes.length);
            MemorySegment inBlob = allocateDataBlob(arena, protectedBytes.length, inData);
            MemorySegment outBlob = arena.allocate(16L, 8L);

            int ok = (int) dpapi.cryptUnprotectData.invokeExact(
                    inBlob,
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                    CRYPTPROTECT_UI_FORBIDDEN,
                    outBlob);
            if (ok == 0) {
                return Optional.empty();
            }
            int outLen = outBlob.get(ValueLayout.JAVA_INT, 0L);
            MemorySegment outPtr = outBlob.get(ValueLayout.ADDRESS, 8L);
            try {
                if (outLen <= 0 || outPtr.equals(MemorySegment.NULL)) {
                    return Optional.empty();
                }
                return Optional.of(outPtr.reinterpret(outLen).toArray(ValueLayout.JAVA_BYTE));
            } finally {
                if (!outPtr.equals(MemorySegment.NULL)) {
                    MemorySegment ignored = (MemorySegment) dpapi.localFree.invokeExact(outPtr);
                }
            }
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    private static MemorySegment allocateDataBlob(Arena arena, int length, MemorySegment dataPtr) {
        MemorySegment blob = arena.allocate(16L, 8L);
        blob.set(ValueLayout.JAVA_INT, 0L, length);
        blob.set(ValueLayout.ADDRESS, 8L, dataPtr);
        return blob;
    }

    private record WindowsDpapi(
            MethodHandle cryptProtectData,
            MethodHandle cryptUnprotectData,
            MethodHandle localFree) {

        static final WindowsDpapi INSTANCE = load();

        private static WindowsDpapi load() {
            if (!WindowsWindowHelper.isWindows() || ValueLayout.ADDRESS.byteSize() != 8L) {
                return null;
            }
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup crypt32 = SymbolLookup.libraryLookup("Crypt32.dll", Arena.global());
                SymbolLookup kernel32 = SymbolLookup.libraryLookup("Kernel32.dll", Arena.global());
                return new WindowsDpapi(
                        linker.downcallHandle(
                                crypt32.find("CryptProtectData").orElseThrow(),
                                FunctionDescriptor.of(
                                        ValueLayout.JAVA_INT,
                                        ValueLayout.ADDRESS,
                                        ValueLayout.ADDRESS,
                                        ValueLayout.ADDRESS,
                                        ValueLayout.ADDRESS,
                                        ValueLayout.ADDRESS,
                                        ValueLayout.JAVA_INT,
                                        ValueLayout.ADDRESS)),
                        linker.downcallHandle(
                                crypt32.find("CryptUnprotectData").orElseThrow(),
                                FunctionDescriptor.of(
                                        ValueLayout.JAVA_INT,
                                        ValueLayout.ADDRESS,
                                        ValueLayout.ADDRESS,
                                        ValueLayout.ADDRESS,
                                        ValueLayout.ADDRESS,
                                        ValueLayout.ADDRESS,
                                        ValueLayout.JAVA_INT,
                                        ValueLayout.ADDRESS)),
                        linker.downcallHandle(
                                kernel32.find("LocalFree").orElseThrow(),
                                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)));
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    public static void setOwnerOnlyPermissions(Path path) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, perms);
            return;
        } catch (UnsupportedOperationException | IOException ignored) {
            // Fall through to Windows ACL handling if applicable.
        }
        if (WindowsWindowHelper.isWindows()) {
            try {
                AclFileAttributeView aclView = Files.getFileAttributeView(path, AclFileAttributeView.class);
                if (aclView != null) {
                    UserPrincipal owner = aclView.getOwner();
                    AclEntry ownerEntry = AclEntry.newBuilder()
                            .setType(AclEntryType.ALLOW)
                            .setPrincipal(owner)
                            .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                            .build();
                    aclView.setAcl(List.of(ownerEntry));
                }
            } catch (IOException | SecurityException ignored) {
                // Best-effort owner-only ACL.
            }
        }
    }
}
