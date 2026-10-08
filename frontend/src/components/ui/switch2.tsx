import { Loader2 } from 'lucide-react';
import { Button } from './button';

/**
 * Minimal two-state toggle styled like a switch. Used in places where the
 * Radix Switch primitive is overkill (e.g. boolean columns in admin
 * tables). Controlled by the parent.
 */
interface Switch2Props {
  checked: boolean;
  onCheckedChange: () => void;
  disabled?: boolean;
  label: string;
}

export function Switch2({ checked, onCheckedChange, disabled, label }: Switch2Props) {
  return (
    <Button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      onClick={onCheckedChange}
      disabled={disabled}
      variant="ghost"
      size="sm"
      className={`h-6 w-11 p-0 rounded-full border ${checked ? 'bg-primary border-primary' : 'bg-muted border-input'} ${disabled ? 'opacity-60' : ''}`}
    >
      {disabled ? (
        <Loader2 className="h-3 w-3 animate-spin text-foreground" />
      ) : (
        <span
          className={`block h-4 w-4 rounded-full bg-background shadow transition-transform ${checked ? 'translate-x-2.5' : '-translate-x-2.5'}`}
        />
      )}
    </Button>
  );
}