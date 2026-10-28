package com.summit.core.runtime.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FileRulePolicyTest {
    @TempDir Path root;
    private FileSecurityRule rule(String path, SecurityAction action) {
        return new FileSecurityRule(path, action, action, action);
    }
    @Test void denialOverridesEarlierCatchAllAndKeepsDirectoryBoundaries() throws Exception {
        Path secret = Files.createDirectories(root.resolve("private"));
        FileRulePolicy policy = new FileRulePolicy(List.of(rule("**", SecurityAction.ALLOW),
                rule(secret.toString(), SecurityAction.DENY)), SecurityAction.ALLOW);
        for (FileOperation operation : FileOperation.values()) {
            SecurityDecision decision = policy.evaluate(secret.resolve("new/deep/file"), operation);
            assertEquals(SecurityAction.DENY, decision.action());
            assertEquals(secret.toString(), decision.matchedRule());
            assertEquals(SecurityAction.ALLOW, policy.evaluate(root.resolve("private-sibling/file"), operation).action());
        }
    }
    @Test void normalizesTraversalAndUsesPerOperationActions() throws Exception {
        Path secret = Files.createDirectories(root.resolve("private"));
        FileRulePolicy policy = new FileRulePolicy(List.of(new FileSecurityRule(secret.toString(),
                SecurityAction.ALLOW, SecurityAction.ASK, SecurityAction.DENY)), SecurityAction.ALLOW);
        Path target = root.resolve("ordinary/../private/new.txt");
        assertEquals(SecurityAction.ALLOW, policy.evaluate(target, FileOperation.READ).action());
        assertEquals(SecurityAction.ASK, policy.evaluate(target, FileOperation.WRITE).action());
        assertEquals(SecurityAction.DENY, policy.evaluate(target, FileOperation.DELETE).action());
        assertEquals(SecurityAction.ALLOW, policy.evaluate(root.getParent().resolve("other-project"), FileOperation.WRITE).action());
    }
    @Test void lexicalAndResolvedSymlinkPathsRemainProtected() throws Exception {
        Path secret = Files.createDirectory(root.resolve("private"));
        Path link = root.resolve("alias");
        try { Files.createSymbolicLink(link, secret); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            org.junit.jupiter.api.Assumptions.abort("Symlink creation unavailable: " + unavailable.getMessage());
        }
        FileRulePolicy realPolicy = new FileRulePolicy(List.of(rule(secret.toString(), SecurityAction.DENY)), SecurityAction.ALLOW);
        assertEquals(SecurityAction.DENY, realPolicy.evaluate(link.resolve("new.txt"), FileOperation.READ).action());
        FileRulePolicy aliasPolicy = new FileRulePolicy(List.of(rule(link.toString(), SecurityAction.DENY)), SecurityAction.ALLOW);
        assertEquals(SecurityAction.DENY, aliasPolicy.evaluate(secret.resolve("new.txt"), FileOperation.READ).action());
    }
    @Test void windowsJunctionCannotBypassProtectedTree() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("windows"));
        Path secret = Files.createDirectory(root.resolve("private"));
        Path link = root.resolve("junction");
        Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), secret.toString()).start();
        assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        try {
            FileRulePolicy policy = new FileRulePolicy(List.of(rule(secret.toString(), SecurityAction.DENY)), SecurityAction.ALLOW);
            assertEquals(SecurityAction.DENY, policy.evaluate(link.resolve("new.txt"), FileOperation.READ).action());
        } finally { Files.delete(link); }
    }
    @Test void rejectsRelativeRulePaths() {
        assertThrows(IllegalArgumentException.class, () -> new FileRulePolicy(List.of(rule("private", SecurityAction.DENY)), SecurityAction.ALLOW));
    }
}
