package com.summit.sandbox.windows;

import com.sun.jna.platform.win32.Crypt32Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Objects;

/**
 * Persists the sandbox account password encrypted with Windows DPAPI (current-user scope).
 */
final class WindowsSandboxCredentialStore {
    private static final String PASSWORD_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#%^&*-_=+";
    private static final int PASSWORD_LENGTH = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private WindowsSandboxCredentialStore() {
    }

    static Path defaultCredentialFile(SandboxMode mode) {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = (localAppData != null && !localAppData.isBlank())
                ? Path.of(localAppData)
                : Path.of(System.getProperty("user.home"), "AppData", "Local");
        String fileName = mode == SandboxMode.READ_WRITE ? "sandbox-rw.dpapi" : "sandbox-ro.dpapi";
        return base.resolve("LingXi").resolve(fileName);
    }

    static String generateStrongPassword() {
        StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            sb.append(PASSWORD_ALPHABET.charAt(RANDOM.nextInt(PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }

    static void save(Path file, String password) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(password, "password");
        Path normalized = file.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] protectedBytes = Crypt32Util.cryptProtectData(password.getBytes(StandardCharsets.UTF_8));
        Files.write(normalized, protectedBytes);
    }

    static String load(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        Path normalized = file.toAbsolutePath().normalize();
        byte[] protectedBytes = Files.readAllBytes(normalized);
        byte[] plainBytes = Crypt32Util.cryptUnprotectData(protectedBytes);
        return new String(plainBytes, StandardCharsets.UTF_8);
    }

    static boolean existsAndReadable(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        try {
            String pwd = load(file);
            return !pwd.isEmpty();
        } catch (RuntimeException | IOException e) {
            return false;
        }
    }
}
