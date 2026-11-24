package com.livingdocs.modules.github.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;

/**
 * Reader for a gzipped tar archive, as produced by GitHub's
 * {@code /tarball/{ref}} endpoint.
 *
 * <h2>Why the tarball and not the contents API</h2>
 *
 * <p>GitHub's contents API returns one directory per request. Reading a
 * whole repository through it therefore costs one HTTP round trip per
 * directory — hundreds of requests for a monorepo — and any traversal
 * that gives up early silently drops the files it never reached. The
 * tarball endpoint returns the entire repository in a single response,
 * which makes the ingest both faster and complete.
 *
 * <h2>Format</h2>
 *
 * <p>A tar stream is a sequence of 512-byte header blocks, each followed
 * by the file's contents padded to a 512-byte boundary. Only regular
 * files are returned; directories, symlinks and other special entries are
 * skipped. GNU long-name entries ({@code L}) are honoured so that paths
 * longer than 100 characters survive.
 *
 * <p>GitHub wraps every path in a single top-level directory named
 * {@code <owner>-<repo>-<sha>}. That prefix is stripped so the paths
 * match the ones GitHub reports elsewhere (and the ones stored in the
 * database).
 *
 * <p>The archive is streamed: only the files accepted by the filter are
 * kept in memory, so a large repository does not have to fit into the
 * heap.
 */
public final class RepositoryArchive {

    private static final int BLOCK_SIZE = 512;

    /**
     * Hard limit on the decompressed size of the archive.
     *
     * <p>A repository archive is read in a single pass, and an
     * unbounded decompression would let a huge repository exhaust the
     * heap. The limit is generous enough for a normal source repository
     * and turns a pathological one into a clean failure.
     */
    static final long MAX_DECOMPRESSED_BYTES = 512L * 1024 * 1024;

    /** Maximum size of a single file that is kept. */
    static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

    private RepositoryArchive() {
    }

    /**
     * Read every regular file of a gzipped tar archive that the filter
     * accepts.
     *
     * @param gzip   the archive bytes
     * @param filter decides which paths are kept
     * @return the file contents keyed by their repository-relative path
     * @throws IOException when the archive cannot be read
     */
    public static Map<String, byte[]> read(InputStream gzip, Predicate<String> filter)
            throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (GZIPInputStream in = new GZIPInputStream(gzip)) {
            byte[] header = new byte[BLOCK_SIZE];
            String pendingLongName = null;
            long total = 0;

            while (true) {
                if (!readFully(in, header, BLOCK_SIZE)) break;
                if (isZeroBlock(header)) break;

                String name = readPath(header);
                long size = readOctal(header, 124, 12);
                char type = (char) header[156];

                // The data of the entry follows the header and is padded
                // to a block boundary.
                int padded = (int) (((size + BLOCK_SIZE - 1) / BLOCK_SIZE) * BLOCK_SIZE);
                byte[] data = in.readNBytes(padded);
                if (data.length < size) break;

                if (type == 'L') {
                    // GNU long name: the real path is stored as this
                    // entry's content, and the next header carries a
                    // truncated placeholder.
                    pendingLongName = new String(data, 0, (int) size,
                            StandardCharsets.UTF_8).trim();
                    continue;
                }
                if (pendingLongName != null) {
                    name = pendingLongName;
                    pendingLongName = null;
                }

                if (type != '0' && type != '\0') continue;

                total += size;
                if (total > MAX_DECOMPRESSED_BYTES) {
                    throw new IOException("Archive exceeds the maximum decompressed size of "
                            + MAX_DECOMPRESSED_BYTES + " bytes");
                }

                String path = stripTopLevelDirectory(name);
                if (path == null || path.isBlank()) continue;
                if (size > MAX_FILE_BYTES) continue;
                if (!filter.test(path)) continue;

                files.put(path, java.util.Arrays.copyOf(data, (int) size));
            }
        }
        return files;
    }

    /**
     * Read the path of a tar entry.
     *
     * <p>Three encodings have to be handled, because tar has no single
     * way of storing a long path:
     *
     * <ul>
     *   <li>The plain {@code name} field holds up to 100 characters.</li>
     *   <li>The {@code prefix} field (offset 345, part of the ustar
     *       extension) holds a further 155 characters that are
     *       <em>prepended</em> to the name. This is what GitHub's
     *       tarball uses for the long paths of a monorepo — a Java
     *       source file such as
     *       {@code backend/src/main/java/com/livingdocs/common/cache/CacheInfoController.java}
     *       does not fit into the name field alone. Ignoring the prefix
     *       therefore truncates every such path to its bare file name,
     *       and the file is then indexed under a path that does not
     *       exist in the repository.</li>
     *   <li>The GNU long-name extension ({@code L} entries) carries the
     *       full path in the entry's own content.</li>
     * </ul>
     */
    private static String readPath(byte[] header) {
        String name = readString(header, 0, 100);
        String prefix = readString(header, 345, 155);
        if (!prefix.isEmpty()) {
            return prefix + "/" + name;
        }
        return name;
    }

    /**
     * Remove the single top-level directory GitHub puts around every
     * archive.
     *
     * @return the repository-relative path, or {@code null} when the
     *         entry has no top-level directory to strip
     */
    static String stripTopLevelDirectory(String name) {
        int slash = name.indexOf('/');
        if (slash < 0) return null;
        String rest = name.substring(slash + 1);
        return rest.isBlank() ? null : rest;
    }

    /**
     * Read exactly {@code length} bytes, or return {@code false} when the
     * stream ended first.
     */
    private static boolean readFully(InputStream in, byte[] buffer, int length) throws IOException {
        int offset = 0;
        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);
            if (read < 0) return false;
            offset += read;
        }
        return true;
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) return false;
        }
        return true;
    }

    private static String readString(byte[] block, int offset, int length) {
        int end = offset;
        while (end < offset + length && block[end] != 0) end++;
        return new String(block, offset, end - offset, StandardCharsets.UTF_8);
    }

    /**
     * Read a numeric tar header field.
     *
     * <p>Tar uses octal, but some producers write the value in base-256
     * (the high bit of the first byte is set) for values that do not fit
     * into an octal field. Both encodings are handled here.
     */
    private static long readOctal(byte[] block, int offset, int length) {
        if ((block[offset] & 0x80) != 0) {
            long value = block[offset] & 0x7f;
            for (int i = offset + 1; i < offset + length; i++) {
                value = (value << 8) | (block[i] & 0xffL);
            }
            return value;
        }
        long value = 0;
        for (int i = offset; i < offset + length; i++) {
            byte b = block[i];
            if (b == 0 || b == ' ') continue;
            if (b < '0' || b > '7') break;
            value = (value << 3) | (b - '0');
        }
        return value;
    }
}
