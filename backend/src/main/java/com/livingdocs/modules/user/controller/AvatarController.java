package com.livingdocs.modules.user.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.user.model.User;
import com.livingdocs.modules.user.service.AvatarStorageService;
import com.livingdocs.modules.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

/**
 * Endpoints for the current user's profile picture.
 *
 * <p>Two flavours live here:
 * <ul>
 *     <li>{@code POST /api/v1/users/me/avatar} — authenticated upload
 *         for the signed-in user.</li>
 *     <li>{@code GET /api/v1/users/avatar/{userId}/{fileName}} — <b>public</b>
 *         read endpoint so that an {@code <img src="…">} tag can be
 *         rendered without attaching an Authorization header.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "Authenticated user profile operations")
public class AvatarController {

    private final AvatarStorageService storageService;
    private final UserService userService;

    public AvatarController(AvatarStorageService storageService, UserService userService) {
        this.storageService = storageService;
        this.userService = userService;
    }

    @PostMapping("/me/avatar")
    @Operation(summary = "Upload or replace the current user's avatar")
    public ResponseEntity<AvatarResponse> upload(@RequestParam("file") MultipartFile file) {
        UUID userId = CurrentUser.requireId();
        User user = userService.getById(userId);

        String previousKey = user.getAvatarUrl();
        String newKey = storageService.store(userId, file);

        user.setAvatarUrl(newKey);
        userService.save(user);

        // Clean up the old file only after the new one is safely stored.
        if (previousKey != null && !previousKey.equals(newKey)) {
            storageService.delete(previousKey);
        }

        return ResponseEntity.ok(new AvatarResponse(
                user.getAvatarUrl(),
                storageService.publicUrl(user.getAvatarUrl())));
    }

    @DeleteMapping("/me/avatar")
    @Operation(summary = "Remove the current user's avatar")
    public ResponseEntity<AvatarResponse> remove() {
        UUID userId = CurrentUser.requireId();
        User user = userService.getById(userId);

        String previousKey = user.getAvatarUrl();
        user.setAvatarUrl(null);
        userService.save(user);

        if (previousKey != null) {
            storageService.delete(previousKey);
        }

        return ResponseEntity.ok(new AvatarResponse(null, null));
    }

    @GetMapping("/avatar/{userId}/{fileName}")
    @Operation(summary = "Public read endpoint for avatar images")
    public ResponseEntity<Resource> serve(@PathVariable String userId,
                                          @PathVariable String fileName) {
        if (userId == null || userId.isBlank() || fileName == null || fileName.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        // Reject anything that looks like a path segment instead of a file.
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            return ResponseEntity.notFound().build();
        }

        String key = "avatars/" + userId + "/" + fileName;
        Path path;
        try {
            path = storageService.resolveWithinRoot(key);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }

        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(path);
        return ResponseEntity.ok()
                .contentType(mediaTypeFor(fileName))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .body(resource);
    }

    private MediaType mediaTypeFor(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (lower.endsWith(".gif")) {
            return MediaType.IMAGE_GIF;
        }
        if (lower.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    /** Response body for upload / delete operations. */
    public record AvatarResponse(String avatarKey, String avatarUrl) {
    }
}
