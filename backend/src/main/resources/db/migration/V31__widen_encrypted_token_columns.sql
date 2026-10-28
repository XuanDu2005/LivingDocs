-- Widen the token columns so the AES-GCM ciphertext always fits.
--
-- The EncryptedStringConverter stores "enc:v1:" + base64(12-byte IV ||
-- ciphertext || 16-byte GCM tag). Base64 expands by 4/3, so a token of
-- N characters needs roughly 4/3 * (N + 28) + 7 characters of column
-- space. The previous widths (255 for GitHub, 500 for OAuth) left no
-- headroom for long-lived fine-grained tokens, and a truncated write
-- would silently corrupt the secret.
ALTER TABLE github_connections
    ALTER COLUMN access_token TYPE VARCHAR(1024);

ALTER TABLE user_oauth_identities
    ALTER COLUMN access_token TYPE VARCHAR(1024),
    ALTER COLUMN refresh_token TYPE VARCHAR(1024);
