package io.github.hectorvent.floci.core.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/** Canonical containment for approved host paths, including leaves not created yet. */
public final class HostPathContainment {
    private HostPathContainment() { }

    public static Path canonical(Path path) throws IOException {
        Path parent = path.toAbsolutePath().normalize();
        Deque<Path> suffix = new ArrayDeque<>();
        while (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            suffix.addFirst(parent.getFileName());
            parent = parent.getParent();
            if (parent == null) {
                throw new IOException("Host path has no accessible ancestor.");
            }
        }
        Path resolved = parent.toRealPath();
        for (Path segment : suffix) {
            resolved = resolved.resolve(segment);
        }
        return resolved;
    }

    public static boolean isUnderRoot(Path path, Path root) throws IOException {
        if (!root.isAbsolute() || root.normalize().getNameCount() == 0) {
            return false;
        }
        return canonical(path).startsWith(canonical(root));
    }
}
