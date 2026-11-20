package com.livingdocs.modules.github.client;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reader for GitHub's repository tarball.
 *
 * <p>The archive is what makes a whole-repository ingest possible in one
 * request. If it silently drops files, the ingest looks successful while
 * the repository stays half-indexed — the same failure the previous
 * directory crawl produced, only harder to spot.
 */
class RepositoryArchiveTest {

    /**
     * Build a gzipped tar archive from the given entries.
     *
     * @param entries path to content, in the order they appear in the archive
     */
    private static byte[] tarball(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            byte[] name = entry.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] content = entry.getValue().getBytes(StandardCharsets.UTF_8);

            // A path that does not fit into the 100-character name field
            // is split across the ustar `prefix` field and the name
            // field, exactly as GitHub's tarball does it.
            String prefix = "";
            if (name.length > 100) {
                int split = name.length - 100;
                // Split on a directory boundary so the prefix holds the
                // leading directories and the name holds the file name.
                int slash = -1;
                for (int i = split; i < name.length - 1; i++) {
                    if (name[i] == '/') slash = i;
                }
                if (slash > 0) {
                    prefix = new String(name, 0, slash, StandardCharsets.UTF_8);
                    name = java.util.Arrays.copyOfRange(name, slash + 1, name.length);
                }
            }

            byte[] header = new byte[512];
            System.arraycopy(name, 0, header, 0, Math.min(name.length, 100));
            if (!prefix.isEmpty()) {
                byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
                System.arraycopy(prefixBytes, 0, header, 345, Math.min(prefixBytes.length, 155));
                // The ustar magic marks a header that uses the prefix.
                System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 6);
            }
            writeOctal(header, 100, 8, 0x1A);            // mode
            writeOctal(header, 108, 8, 0);              // uid
            writeOctal(header, 116, 8, 0);              // gid
            writeOctal(header, 124, 12, content.length); // size
            writeOctal(header, 136, 12, 0);             // mtime
            header[156] = '0';                          // regular file
            writeOctal(header, 263, 6, 0);              // checksum
            // The checksum field is filled in with spaces while the
            // checksum is computed, so it has to be cleared again.
            for (int i = 148; i < 156; i++) header[i] = ' ';
            int checksum = 0;
            for (byte b : header) checksum += (b & 0xff);
            writeOctal(header, 148, 8, checksum);

            raw.write(header);
            raw.write(content);
            int padding = (512 - (content.length % 512)) % 512;
            raw.write(new byte[padding]);
        }
        // Two zero blocks terminate a tar stream.
        raw.write(new byte[1024]);

        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(gzipped)) {
            gzip.write(raw.toByteArray());
        }
        return gzipped.toByteArray();
    }

    private static void writeOctal(byte[] block, int offset, int length, long value) {
        String octal = Long.toOctalString(value);
        // The field is right-aligned and NUL-terminated.
        StringBuilder sb = new StringBuilder();
        while (sb.length() + octal.length() < length - 1) sb.append('0');
        sb.append(octal);
        byte[] bytes = sb.toString().getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, block, offset, Math.min(bytes.length, length - 1));
    }

    @Test
    void readsEveryFileOfTheArchive() throws IOException {
        byte[] archive = tarball(Map.of(
                "owner-repo-abc123/README.md", "# Hello",
                "owner-repo-abc123/src/index.ts", "export const x = 1;"));

        Map<String, byte[]> files = RepositoryArchive.read(
                new java.io.ByteArrayInputStream(archive), path -> true);

        assertEquals(2, files.size());
        assertEquals("# Hello",
                new String(files.get("README.md"), StandardCharsets.UTF_8));
        assertEquals("export const x = 1;",
                new String(files.get("src/index.ts"), StandardCharsets.UTF_8));
    }

    @Test
    void stripsTheTopLevelDirectory() {
        // GitHub wraps every archive in one directory named
        // `<owner>-<repo>-<sha>`; the stored paths must not carry it.
        assertEquals("src/main/java/App.java",
                RepositoryArchive.stripTopLevelDirectory("XuanDu2005-LivingDocs-241dce8/src/main/java/App.java"));
        assertNull(RepositoryArchive.stripTopLevelDirectory("README.md"));
    }

    @Test
    void appliesTheFilter() throws IOException {
        byte[] archive = tarball(Map.of(
                "repo-abc/src/index.ts", "export const x = 1;",
                "repo-abc/docs/readme.md", "# Hello"));

        Map<String, byte[]> files = RepositoryArchive.read(
                new java.io.ByteArrayInputStream(archive),
                path -> path.endsWith(".ts"));

        assertEquals(1, files.size());
        assertTrue(files.containsKey("src/index.ts"));
        assertFalse(files.containsKey("docs/readme.md"));
    }

    @Test
    void readsEmptyContent() throws IOException {
        byte[] archive = tarball(Map.of("repo-abc/empty.txt", ""));

        Map<String, byte[]> files = RepositoryArchive.read(
                new java.io.ByteArrayInputStream(archive), path -> true);

        assertEquals(1, files.size());
        assertArrayEquals(new byte[0], files.get("empty.txt"));
    }

    @Test
    void readsDeeplyNestedFiles() throws IOException {
        // The bug this replaces stopped the crawl after four levels, so
        // the Java sources of the backend — nine levels deep — were never
        // seen. A path of any depth must survive the archive read.
        String deep = "repo-abc/backend/src/main/java/com/livingdocs/modules/code/"
                + "service/CodeEntityExtractionService.java";
        byte[] archive = tarball(Map.of(deep, "public class CodeEntityExtractionService {}"));

        Map<String, byte[]> files = RepositoryArchive.read(
                new java.io.ByteArrayInputStream(archive), path -> true);

        assertEquals(1, files.size());
        // The top-level directory is stripped, so the key is the
        // repository-relative path.
        assertTrue(files.containsKey(
                "backend/src/main/java/com/livingdocs/modules/code/service/CodeEntityExtractionService.java"),
                "expected the deeply nested file, got " + files.keySet());
    }

    @Test
    void readsPathsLongerThanTheNameField() throws IOException {
        // A tar header holds at most 100 characters of the path in its
        // name field; the rest goes into the ustar `prefix` field. GitHub
        // writes the long paths of a monorepo that way, so a reader that
        // only reads the name field sees every one of them as a bare file
        // name — the file is then indexed under a path that does not
        // exist in the repository.
        String longPath = "XuanDu2005-LivingDocs-241dce8/backend/src/main/java/com/livingdocs/"
                + "modules/common/crypto/EncryptedStringConverter.java";
        assertTrue(longPath.length() > 100,
                "the test path must exceed the 100-character name field");

        byte[] archive = tarball(Map.of(
                longPath, "public class EncryptedStringConverter {}"));

        Map<String, byte[]> files = RepositoryArchive.read(
                new java.io.ByteArrayInputStream(archive), path -> true);

        assertEquals(1, files.size());
        assertTrue(files.containsKey(
                "backend/src/main/java/com/livingdocs/modules/common/crypto/EncryptedStringConverter.java"),
                "expected the full path, got " + files.keySet());
    }
}
