package com.livingdocs.modules.github.client;

import org.junit.jupiter.api.Test;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Verifies the archive reader against a real GitHub repository tarball.
 *
 * <p>This is a manual check rather than an automated test: it needs the
 * archive of a real repository, which is not checked in. The archive of
 * {@code XuanDu2005/LivingDocs} is a monorepo whose Java sources sit
 * nine to eleven levels deep — exactly the files the previous
 * directory crawl could not reach.
 *
 * <p>Run it with the archive on disk:
 * <pre>
 *   mvn test -Dtest=RepositoryArchiveRealWorldIT \
 *            -Darchive.path=/path/to/tarball.tgz
 * </pre>
 */
class RepositoryArchiveRealWorldIT {

    @Test
    void readsTheRealMonorepoArchive() throws Exception {
        String path = System.getProperty("archive.path");
        if (path == null || path.isBlank()) {
            System.out.println("archive.path is not set — skipping the real-archive check");
            return;
        }
        Path archivePath = Path.of(path);
        if (!Files.exists(archivePath)) {
            throw new IllegalStateException("No archive at " + archivePath);
        }

        Map<String, byte[]> files;
        try (InputStream in = new FileInputStream(archivePath.toFile())) {
            files = RepositoryArchive.read(in, p -> true);
        }

        System.out.println("read " + files.size() + " file(s) from the archive");

        // Every path must be repository-relative — no leftover
        // top-level directory, no absolute path.
        for (String key : files.keySet()) {
            if (key.startsWith("/") || key.contains("..")) {
                throw new IllegalStateException("Unexpected path in the archive: " + key);
            }
        }

        // The Java sources of the backend must be present under their
        // full, repository-relative path. This is the assertion that
        // the previous implementation failed.
        long javaFiles = files.keySet().stream()
                .filter(p -> p.startsWith("backend/src/main/java/"))
                .count();
        System.out.println("backend Java files: " + javaFiles);
        if (javaFiles == 0) {
            throw new IllegalStateException(
                    "The backend Java sources are missing from the archive");
        }

        // The longest paths are the ones that need the ustar prefix
        // field; they must not be truncated to their file name.
        String longest = files.keySet().stream()
                .max((a, b) -> Integer.compare(a.length(), b.length()))
                .orElseThrow();
        System.out.println("longest path (" + longest.length() + "): " + longest);
        if (!longest.contains("/")) {
            throw new IllegalStateException(
                    "The longest path was truncated to its file name: " + longest);
        }
    }
}
