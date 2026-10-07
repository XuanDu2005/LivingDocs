import {
  ChangeEvent,
  ClipboardEvent,
  KeyboardEvent,
  useCallback,
  useRef,
} from 'react';

import { cn } from '../../lib/utils';

interface OtpInputProps {
  /** Current OTP value, e.g. "482913". */
  value: string;
  /** Called when the user edits the OTP. */
  onChange: (next: string) => void;
  /** Number of digits (default 6). */
  length?: number;
  /** Disabled state. */
  disabled?: boolean;
  /** Auto-focus the first box on mount. */
  autoFocus?: boolean;
  /** Optional className for the outer container. */
  className?: string;
  /** Called when all digits are filled. */
  onComplete?: (value: string) => void;
}

/**
 * A 6-digit OTP input that supports auto-advance, paste, and keyboard
 * navigation. Pure controlled component — pass {@code value} and
 * {@code onChange} like a regular text input.
 */
export function OtpInput({
  value,
  onChange,
  length = 6,
  disabled = false,
  autoFocus = true,
  className,
  onComplete,
}: OtpInputProps) {
  const refs = useRef<Array<HTMLInputElement | null>>([]);

  const digits = Array.from(value.padEnd(length, ' ').slice(0, length));

  const setRef = useCallback(
    (idx: number) => (el: HTMLInputElement | null) => {
      refs.current[idx] = el;
    },
    [],
  );

  const update = (next: string) => {
    const cleaned = next.replace(/\D/g, '').slice(0, length);
    onChange(cleaned);
    if (cleaned.length === length) onComplete?.(cleaned);
  };

  const onChangeOne = (idx: number) => (e: ChangeEvent<HTMLInputElement>) => {
    const ch = e.target.value.replace(/\D/g, '').slice(-1);
    const chars = digits.slice();
    chars[idx] = ch || ' ';
    const next = chars.join('').replace(/ /g, '');
    update(next);
    if (ch && idx < length - 1) {
      refs.current[idx + 1]?.focus();
      refs.current[idx + 1]?.select();
    }
  };

  const onKeyDown = (idx: number) => (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Backspace' && !digits[idx].trim() && idx > 0) {
      e.preventDefault();
      refs.current[idx - 1]?.focus();
    } else if (e.key === 'ArrowLeft' && idx > 0) {
      e.preventDefault();
      refs.current[idx - 1]?.focus();
    } else if (e.key === 'ArrowRight' && idx < length - 1) {
      e.preventDefault();
      refs.current[idx + 1]?.focus();
    }
  };

  const onPaste = (e: ClipboardEvent<HTMLInputElement>) => {
    const pasted = e.clipboardData.getData('text');
    if (/\d{4,}/.test(pasted)) {
      e.preventDefault();
      update(pasted);
      const last = Math.min(pasted.length, length) - 1;
      refs.current[last]?.focus();
    }
  };

  return (
    <div
      className={cn('flex items-center justify-center gap-2', className)}
      role="group"
      aria-label="One-time code"
    >
      {digits.map((d, i) => (
        <input
          key={i}
          ref={setRef(i)}
          type="text"
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={1}
          value={d.trim()}
          disabled={disabled}
          autoFocus={autoFocus && i === 0}
          onChange={onChangeOne(i)}
          onKeyDown={onKeyDown(i)}
          onPaste={onPaste}
          aria-label={`Digit ${i + 1}`}
          className={cn(
            'h-12 w-12 rounded-md border border-input bg-background text-center text-xl font-semibold',
            'focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/30',
            'disabled:cursor-not-allowed disabled:opacity-50',
          )}
        />
      ))}
    </div>
  );
}
