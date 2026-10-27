import { useEffect, useState } from 'react';
import { cn } from '../../lib/utils';
import { resolveAvatarUrl } from '../../services/avatar';

export interface UserAvatarProps {
  /** Public avatar URL as returned by the backend (`null` = no picture). */
  src?: string | null;
  /** Used to derive the initials fallback. */
  name?: string | null;
  /** Used as a secondary fallback for the initials. */
  email?: string | null;
  size?: 'xs' | 'sm' | 'md' | 'lg' | 'xl';
  className?: string;
}

const SIZE_CLASSES: Record<NonNullable<UserAvatarProps['size']>, string> = {
  xs: 'h-6 w-6 text-[10px]',
  sm: 'h-8 w-8 text-xs',
  md: 'h-10 w-10 text-sm',
  lg: 'h-16 w-16 text-lg',
  xl: 'h-24 w-24 text-2xl',
};

/**
 * Renders the user's profile picture, falling back to their initials
 * when no image has been uploaded.
 *
 * <p>The initials are derived from the display name (first letters of
 * the first two words); when there is no display name we fall back to
 * the first character of the email address.
 */
export function UserAvatar({
  src,
  name,
  email,
  size = 'md',
  className,
}: UserAvatarProps) {
  const [failed, setFailed] = useState(false);
  const resolved = resolveAvatarUrl(src);

  // A new picture deserves a fresh attempt — otherwise a previous
  // load error would keep showing the initials fallback forever.
  useEffect(() => {
    setFailed(false);
  }, [resolved]);

  const sizeClass = SIZE_CLASSES[size];

  if (resolved && !failed) {
    return (
      <img
        src={resolved}
        alt={name || email || 'avatar'}
        className={cn(
          'shrink-0 rounded-full border border-border object-cover',
          sizeClass,
          className,
        )}
        onError={() => setFailed(true)}
      />
    );
  }

  return (
    <div
      aria-hidden="true"
      className={cn(
        'flex shrink-0 items-center justify-center rounded-full bg-primary/15 font-semibold uppercase text-primary',
        sizeClass,
        className,
      )}
    >
      {initialsOf(name, email)}
    </div>
  );
}

/** Derives up to two initials from a display name or email address. */
export function initialsOf(name?: string | null, email?: string | null): string {
  const source = (name ?? '').trim();
  if (source) {
    const parts = source.split(/\s+/).filter(Boolean);
    if (parts.length === 1) {
      return parts[0].slice(0, 2);
    }
    return (parts[0][0] ?? '') + (parts[parts.length - 1][0] ?? '');
  }
  const mail = (email ?? '').trim();
  return mail ? mail.slice(0, 2) : '?';
}
