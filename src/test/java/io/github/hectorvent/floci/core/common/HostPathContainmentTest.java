package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostPathContainmentTest {
    @TempDir
    Path temporary;

    @Test
    void missingChildrenUnderApprovedRootsAreAccepted() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("code"));
        assertTrue(HostPathContainment.isUnderRoot(root.resolve("new/app"), root));
        assertTrue(HostPathContainment.isUnderRoot(root, root));
        assertFalse(HostPathContainment.isUnderRoot(temporary.resolve("code-evil"), root));
        assertFalse(HostPathContainment.isUnderRoot(root.resolve("../outside"), root));
        assertFalse(HostPathContainment.isUnderRoot(root, Path.of("/")));
    }

    @Test
    void symlinkCannotEscapeApprovedRootEvenWithMissingLeaf() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("code"));
        Path outside = Files.createDirectory(temporary.resolve("private"));
        Files.createSymbolicLink(root.resolve("escape"), outside);
        assertFalse(HostPathContainment.isUnderRoot(root.resolve("escape"), root));
        assertFalse(HostPathContainment.isUnderRoot(root.resolve("escape/new/app"), root));
    }
}
