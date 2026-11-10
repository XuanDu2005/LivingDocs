import { useTranslation } from 'react-i18next';
import { AlertCircle } from 'lucide-react';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from './ui/select';

export type OutputFormat = 'MARKDOWN' | 'HTML' | 'PDF';

/**
 * The output formats the renderer can actually produce.
 *
 * <p>PDF is listed separately because it is <em>not</em> implemented
 * yet: the renderer falls back to markdown and logs a warning, so a
 * template that asks for PDF would quietly produce something else. The
 * option is therefore disabled in the picker and explained underneath.
 */
const SUPPORTED_FORMATS: OutputFormat[] = ['MARKDOWN', 'HTML'];
const UNSUPPORTED_FORMATS: OutputFormat[] = ['PDF'];

/**
 * Picker for a template's output format.
 *
 * <p>The PDF option is shown but disabled, with a tooltip that says why.
 * The backend rejects PDF as well, so a value that somehow ends up
 * being PDF is normalised to Markdown by the caller before it is sent.
 */
export default function OutputFormatSelect({
  id,
  value,
  onChange,
  disabled,
  notice,
}: {
  id?: string;
  value: OutputFormat;
  onChange: (value: OutputFormat) => void;
  disabled?: boolean;
  /** Overrides the hint underneath the picker. */
  notice?: string;
}) {
  const { t } = useTranslation();
  const unsupported = UNSUPPORTED_FORMATS.includes(value);

  return (
    <div className="space-y-1.5">
      <Select
        value={value}
        onValueChange={(v) => onChange(v as OutputFormat)}
        disabled={disabled}
      >
        <SelectTrigger id={id}><SelectValue /></SelectTrigger>
        <SelectContent>
          {SUPPORTED_FORMATS.map((f) => (
            <SelectItem key={f} value={f}>
              {t(`templates.outputFormat_${f}`)}
            </SelectItem>
          ))}
          {UNSUPPORTED_FORMATS.map((f) => (
            <SelectItem
              key={f}
              value={f}
              disabled
              title={t('templates.outputFormatNotSupported')}
            >
              {t(`templates.outputFormat_${f}`)}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      <p
        className="flex items-start gap-1.5 text-xs text-muted-foreground"
        title={t('templates.outputFormatNotSupported')}
      >
        <AlertCircle className="mt-0.5 h-3 w-3 flex-shrink-0" aria-hidden="true" />
        <span>
          {notice ?? (unsupported
            ? t('templates.outputFormatPdfUnsupportedSelected')
            : t('templates.outputFormatPdfHint'))}
        </span>
      </p>
    </div>
  );
}
