import { useTranslation } from 'react-i18next';
import { Check, Globe } from 'lucide-react';
import { Button } from './ui/button';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem,
  DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger,
} from './ui/dropdown-menu';
import { SUPPORTED_LANGUAGES } from '../i18n/config';

/**
 * Compact language selector. Placed in the sidebar footer next to the
 * theme toggle. The current language is persisted in localStorage by
 * i18next-browser-languagedetector under the {@code i18nextLng} key.
 */
export function LanguageSwitcher() {
  const { t, i18n } = useTranslation();
  const current = (i18n.resolvedLanguage ?? i18n.language ?? 'en').split('-')[0];
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="ghost"
          size="sm"
          className="w-full justify-start"
          aria-label={t('common.language')}
        >
          <Globe className="h-4 w-4" />
          {current === 'vi' ? t('common.vietnamese') : t('common.english')}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="w-48">
        <DropdownMenuLabel>{t('common.language')}</DropdownMenuLabel>
        <DropdownMenuSeparator />
        {SUPPORTED_LANGUAGES.map((code) => (
          <DropdownMenuItem
            key={code}
            onSelect={() => {
              void i18n.changeLanguage(code);
            }}
          >
            <span className="flex-1">
              {code === 'vi' ? t('common.vietnamese') : t('common.english')}
            </span>
            {current === code && <Check className="h-4 w-4" />}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
