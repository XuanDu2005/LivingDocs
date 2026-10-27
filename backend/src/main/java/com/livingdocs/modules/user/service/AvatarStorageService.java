package com.livingdocs.modules.user.service;

import com.livingdocs.common.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Stores and serves user avatars on the local filesystem.
 *
 * <p>Design decisions:
 * <ul>
 *     <li><b>Local disk, not the database.</b> Avatars are binary blobs
 *     that never need relational queries, so keeping them on disk (with a
 *     Docker volume) keeps PostgreSQL small and lets a CDN/object store
 *     take over later without changing the API contract.</li>
 *     <li><b>Stored as a relative key.</b> The database holds
 *     {@code avatars/&lt;user-id&gt;/&lt;uuid&gt;.&lt;ext&gt;}; the public URL is
 *     derived at request time. This keeps the host/port out of the DB.</li>
 *     <li><b>Content-type sniffing via the magic bytes.</b> The client
 *     can lie about {@code Content-Type}, so we verify the real type
 *     before accepting the file.</li>
 *     <li><b>Re-encoded to PNG.</b> Uploading a huge JPEG gets resized
 *     and compressed, which keeps the payload small and strips metadata
 *     (EXIF GPS coordinates, etc.) that a user would not expect us to
 *     keep.</li>
 * </ul>
 */
@Service
public class AvatarStorageService {

    private static final Logger log = LoggerFactory.getLogger(AvatarStorageService.class);

    /** Image types we accept, keyed by the extension we store them under. */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp");

    /** Max upload size in bytes (5 MB). Enforced before decoding. */
    private static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024;

    /** Avatars are square; longer edge of the source image is capped at this. */
    private static final int MAX_DIMENSION = 512;

    private final Path storageRoot;
    private final String publicBaseUrl;

    public AvatarStorageService(
            @Value("${app.avatar.storage-dir:./data/avatars}") String storageDir,
            @Value("${app.avatar.public-base-url:/api/v1/users/avatar}") String publicBaseUrl) {
        this.storageRoot = Paths.get(storageDir).toAbsolutePath().normalize();
        this.publicBaseUrl = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
        try {
            Files.createDirectories(this.storageRoot);
            log.info("Avatar storage initialised at {}", this.storageRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create avatar storage dir: " + this.storageRoot, e);
        }
    }

    /**
     * Validates and stores an uploaded avatar, replacing any previous one.
     *
     * @param userId  owner of the avatar
     * @param file    the uploaded multipart file
     * @return the relative storage key (e.g. {@code avatars/&lt;id&gt;/&lt;uuid&gt;.png})
     */
    public String store(UUID userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Avatar file is empty");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new BadRequestException("Avatar must be smaller than 5 MB");
        }

        byte[] bytes;
        try (InputStream in = file.getInputStream()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new BadRequestException("Could not read the uploaded avatar");
        }

        String detected = detectImageType(bytes);
        if (detected == null) {
            throw new BadRequestException(
                    "Unsupported image format. Please upload a PNG, JPEG, GIF or WebP file.");
        }
        if (!ALLOWED_EXTENSIONS.contains(detected)) {
            throw new BadRequestException("Unsupported image format: " + detected);
        }

        byte[] normalized = normalizeToPng(bytes, detected);
        String key = buildKey(userId, "png");

        Path target = resolveWithinRoot(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, normalized);
        } catch (IOException e) {
            log.error("Failed to write avatar for user {}: {}", userId, e.getMessage());
            throw new IllegalStateException("Could not store the avatar", e);
        }

        log.info("Stored avatar for user {} at key {}", userId, key);
        return key;
    }

    /**
     * Deletes a previously stored avatar. Missing files are ignored so the
     * operation is idempotent.
     */
    public void delete(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            Path target = resolveWithinRoot(key);
            if (Files.exists(target)) {
                Files.delete(target);
                log.info("Deleted avatar {}", key);
            }
        } catch (IOException | RuntimeException e) {
            // A failed cleanup must never break the request that triggered it.
            log.warn("Could not delete avatar {}: {}", key, e.getMessage());
        }
    }

    /**
     * Resolves a storage key to an on-disk path, refusing any key that
     * would escape the storage root (path traversal).
     */
    public Path resolveWithinRoot(String key) {
        Path resolved = storageRoot.resolve(key).normalize();
        if (!resolved.startsWith(storageRoot)) {
            throw new BadRequestException("Invalid avatar path");
        }
        return resolved;
    }

    /**
     * Public URL for a storage key, or {@code null} when there is none.
     *
     * <p>The storage key looks like {@code avatars/<user-id>/<file>};
     * the public URL drops the leading {@code avatars/} segment so the
     * route stays {@code /users/avatar/<user-id>/<file>}.
     */
    public String publicUrl(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String relative = key;
        if (relative.startsWith("avatars/")) {
            relative = relative.substring("avatars/".length());
        }
        return publicBaseUrl + "/" + relative;
    }

    private String buildKey(UUID userId, String extension) {
        return "avatars/" + userId + "/" + UUID.randomUUID() + "." + extension;
    }

    /**
     * Sniffs the real image type from the magic bytes, ignoring whatever
     * the client claimed in its {@code Content-Type} header.
     *
     * @return the detected extension, or {@code null} when the bytes are
     *         not a recognised image
     */
    private String detectImageType(byte[] bytes) {
        if (bytes.length < 12) {
            return null;
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "png";
        }
        // JPEG: FF D8 FF
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        // GIF: "GIF8"
        if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') {
            return "gif";
        }
        // WEBP: "RIFF" .... "WEBP"
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "webp";
        }
        return null;
    }

    /**
     * Decodes the image, downscales it to {@link #MAX_DIMENSION} and
     * re-encodes it as PNG. This strips EXIF metadata and keeps the file
     * small.
     *
     * <p>WebP is passed through untouched because {@link ImageIO} cannot
     * decode it without an extra plugin; the browser renders it fine.
     */
    private byte[] normalizeToPng(byte[] bytes, String detectedType) {
        if ("webp".equals(detectedType)) {
            return bytes;
        }
        try {
            BufferedImage original = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (original == null) {
                throw new BadRequestException("The uploaded file is not a readable image");
            }

            int longest = Math.max(original.getWidth(), original.getHeight());
            if (longest <= 0) {
                throw new BadRequestException("The uploaded image has no pixels");
            }

            double scale = longest > MAX_DIMENSION ? (double) MAX_DIMENSION / longest : 1.0;
            int targetW = Math.max(1, (int) Math.round(original.getWidth() * scale));
            int targetH = Math.max(1, (int) Math.round(original.getHeight() * scale));

            BufferedImage scaled = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
            var g = scaled.createGraphics();
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(original, 0, 0, targetW, targetH, null);
            } finally {
                g.dispose();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(scaled, "png", out)) {
                throw new BadRequestException("Could not process the uploaded image");
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new BadRequestException("Could not decode the uploaded image");
        }
    }

    /** Visible for tests / diagnostics. */
    public Path getStorageRoot() {
        return storageRoot;
    }

    /** Exposed so callers can build a public URL without a second lookup. */
    public String publicBaseUrl() {
        return publicBaseUrl;
    }

    /** Lower-cased extension helper kept for clarity. */
    static String normalizeExtension(String ext) {
        return ext == null ? "" : ext.toLowerCase(Locale.ROOT);
    }
}
