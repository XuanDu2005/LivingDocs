import { ChangeEvent, useEffect, useRef, useState } from 'react';
import { Camera, Loader2, Trash2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';

import { Button } from '../ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '../ui/dialog';
import { UserAvatar } from './UserAvatar';
import { AvatarCropper } from './AvatarCropper';
import { removeAvatar, uploadAvatar } from '../../services/avatar';
import { describeError } from '../../services/auth';

export interface AvatarUploaderProps {
  /** Current avatar URL (or `null`). */
  avatarUrl: string | null;
  /** Display name, used for the initials fallback. */
  displayName: string | null;
  /** Email, used as a secondary fallback for the initials. */
  email: string | null;
  /** Called after the avatar changed so the caller can refresh the session. */
  onChanged: (avatarUrl: string | null) => void | Promise<void>;
}

const ACCEPTED_TYPES = ['image/png', 'image/jpeg', 'image/gif', 'image/webp'];
const MAX_BYTES = 5 * 1024 * 1024;

/**
 * Avatar picker with preview, upload and remove actions.
 *
 * <p>When a file is chosen the user is taken to a crop dialog where they
 * can reposition and zoom the picture; only the cropped result is
 * uploaded, so the original never leaves the browser.
 *
 * <p>The file is validated in the browser for a quick error message, but
 * the backend re-validates it by inspecting the magic bytes — the
 * client-side check is only a convenience, not a security boundary.
 */
export function AvatarUploader({
  avatarUrl,
  displayName,
  email,
  onChanged,
}: AvatarUploaderProps) {
  const { t } = useTranslation();
  const inputRef = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  // Crop dialog state.
  const [cropSrc, setCropSrc] = useState<string | null>(null);
  const objectUrlRef = useRef<string | null>(null);

  // Revoke the object URL when it is no longer needed so the blob is
  // released instead of leaking memory.
  useEffect(() => {
    return () => {
      if (objectUrlRef.current) {
        URL.revokeObjectURL(objectUrlRef.current);
      }
    };
  }, []);

  async function handleFileChange(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    // Reset the input so picking the same file again still fires onChange.
    e.target.value = '';
    if (!file) return;

    setError(null);
    setMessage(null);

    if (!ACCEPTED_TYPES.includes(file.type)) {
      setError(t('profile.avatar.invalidType'));
      return;
    }
    if (file.size > MAX_BYTES) {
      setError(t('profile.avatar.tooLarge'));
      return;
    }

    // Hand the picture to the cropper instead of uploading it directly.
    if (objectUrlRef.current) {
      URL.revokeObjectURL(objectUrlRef.current);
    }
    const url = URL.createObjectURL(file);
    objectUrlRef.current = url;
    setCropSrc(url);
  }

  async function handleCrop(croppedFile: File) {
    setBusy(true);
    try {
      const result = await uploadAvatar(croppedFile);
      await onChanged(result.avatarUrl);
      setMessage(t('profile.avatar.updated'));
      closeCropper();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  function closeCropper() {
    if (objectUrlRef.current) {
      URL.revokeObjectURL(objectUrlRef.current);
      objectUrlRef.current = null;
    }
    setCropSrc(null);
  }

  async function handleRemove() {
    setError(null);
    setMessage(null);
    setBusy(true);
    try {
      const result = await removeAvatar();
      await onChanged(result.avatarUrl);
      setMessage(t('profile.avatar.removed'));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-3">
      <div className="flex items-center gap-4">
        <div className="relative">
          <UserAvatar src={avatarUrl} name={displayName} email={email} size="xl" />
          {busy && (
            <div className="absolute inset-0 flex items-center justify-center rounded-full bg-background/70">
              <Loader2 className="h-6 w-6 animate-spin text-primary" />
            </div>
          )}
        </div>

        <div className="space-y-2">
          <div className="flex flex-wrap gap-2">
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={busy}
              onClick={() => inputRef.current?.click()}
            >
              <Camera className="mr-2 h-4 w-4" />
              {avatarUrl ? t('profile.avatar.change') : t('profile.avatar.upload')}
            </Button>

            {avatarUrl && (
              <Button
                type="button"
                variant="ghost"
                size="sm"
                disabled={busy}
                onClick={handleRemove}
              >
                <Trash2 className="mr-2 h-4 w-4" />
                {t('profile.avatar.remove')}
              </Button>
            )}
          </div>
          <p className="text-xs text-muted-foreground">
            {t('profile.avatar.hint')}
          </p>
        </div>
      </div>

      <input
        ref={inputRef}
        type="file"
        accept={ACCEPTED_TYPES.join(',')}
        className="hidden"
        onChange={handleFileChange}
      />

      {error && (
        <div className="rounded-md border border-destructive/50 bg-destructive/10 p-2 text-sm text-destructive">
          {error}
        </div>
      )}
      {message && (
        <div className="rounded-md border border-emerald-500/40 bg-emerald-500/10 p-2 text-sm text-emerald-700 dark:text-emerald-300">
          {message}
        </div>
      )}

      {/* ---- Crop dialog ---- */}
      <Dialog open={cropSrc !== null} onOpenChange={(open) => !open && closeCropper()}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>{t('profile.avatar.cropTitle')}</DialogTitle>
            <DialogDescription>{t('profile.avatar.cropDesc')}</DialogDescription>
          </DialogHeader>
          {cropSrc && (
            <AvatarCropper
              src={cropSrc}
              onCrop={handleCrop}
              onCancel={closeCropper}
            />
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}
