import * as React from 'react';
import { Eye, EyeOff } from 'lucide-react';

import { cn } from '../../lib/utils';
import { Input } from './input';
import { Button } from './button';

export interface PasswordInputProps
  extends Omit<React.InputHTMLAttributes<HTMLInputElement>, 'type'> {
  /**
   * Optional label rendered above the field. When provided, the input
   * keeps the same vertical rhythm as the rest of the form.
   */
  label?: string;
  /**
   * When true, the eye toggle button is hidden (default false).
   */
  hideToggle?: boolean;
  /**
   * If provided, shows a strength hint under the field. Values are
   * matched against common password strength buckets.
   */
  showStrength?: boolean;
}

/**
 * Evaluate password strength on a 0-4 scale (matching zxcvbn buckets):
 *  0 — empty
 *  1 — very weak (length < 8 or trivial guess)
 *  2 — weak
 *  3 — reasonable
 *  4 — strong
 */
export function evaluatePasswordStrength(value: string): {
  score: 0 | 1 | 2 | 3 | 4;
  label: string;
} {
  if (!value) return { score: 0, label: 'Chưa nhập' };

  const hasLower = /[a-z]/.test(value);
  const hasUpper = /[A-Z]/.test(value);
  const hasDigit = /\d/.test(value);
  const hasSymbol = /[^A-Za-z0-9]/.test(value);
  const longEnough = value.length >= 12;

  const variety = [hasLower, hasUpper, hasDigit, hasSymbol].filter(Boolean).length;

  if (value.length < 8) return { score: 1, label: 'Quá ngắn' };
  if (variety <= 1) return { score: 1, label: 'Yếu' };
  if (variety === 2 && !longEnough) return { score: 2, label: 'Trung bình' };
  if (variety === 3 || (variety === 2 && longEnough)) {
    return { score: 3, label: 'Khá tốt' };
  }
  return { score: 4, label: 'Mạnh' };
}

const STRENGTH_BAR: Record<0 | 1 | 2 | 3 | 4, string> = {
  0: 'bg-muted',
  1: 'bg-red-500',
  2: 'bg-orange-500',
  3: 'bg-amber-500',
  4: 'bg-emerald-500',
};

/**
 * Password input with a built-in visibility toggle (eye icon) and
 * optional strength indicator. Drop-in replacement for {@link Input}
 * when the field is a password.
 */
const PasswordInput = React.forwardRef<HTMLInputElement, PasswordInputProps>(
  (
    { className, label, hideToggle = false, showStrength = false, value, ...props },
    ref,
  ) => {
    const [visible, setVisible] = React.useState(false);
    const stringValue = typeof value === 'string' ? value : '';
    const strength = showStrength
      ? evaluatePasswordStrength(stringValue)
      : null;

    return (
      <div className="space-y-1.5">
        {label && (
          <label
            htmlFor={props.id}
            className="text-sm font-medium leading-none text-foreground/90"
          >
            {label}
          </label>
        )}
        <div className="relative">
          <Input
            ref={ref}
            type={visible ? 'text' : 'password'}
            value={value}
            className={cn('pr-10', className)}
            {...props}
          />
          {!hideToggle && (
            <Button
              type="button"
              variant="ghost"
              size="icon"
              onClick={() => setVisible((v) => !v)}
              aria-label={visible ? 'Ẩn mật khẩu' : 'Hiện mật khẩu'}
              aria-pressed={visible}
              tabIndex={-1}
              className="absolute right-1 top-1/2 h-7 w-7 -translate-y-1/2 text-muted-foreground hover:text-foreground"
            >
              {visible ? (
                <EyeOff className="h-4 w-4" aria-hidden="true" />
              ) : (
                <Eye className="h-4 w-4" aria-hidden="true" />
              )}
            </Button>
          )}
        </div>
        {showStrength && strength && stringValue.length > 0 && (
          <div className="space-y-1 pt-1">
            <div className="flex gap-1">
              {[1, 2, 3, 4].map((i) => (
                <span
                  key={i}
                  className={cn(
                    'h-1 flex-1 rounded-full transition-colors',
                    strength.score >= i
                      ? STRENGTH_BAR[strength.score]
                      : 'bg-muted',
                  )}
                />
              ))}
            </div>
            <p className="text-xs text-muted-foreground">
              Độ mạnh: <span className="font-medium">{strength.label}</span>
            </p>
          </div>
        )}
      </div>
    );
  },
);
PasswordInput.displayName = 'PasswordInput';

export { PasswordInput };
