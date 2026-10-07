import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import LanguageDetector from 'i18next-browser-languagedetector';
import en from './en.json';
import vi from './vi.json';

/**
 * i18n bootstrap. Languages are loaded eagerly (small) and persisted to
 * localStorage by {@link LanguageDetector}; on first visit the user's
 * browser language is honoured as a fallback.
 */
i18n
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    fallbackLng: 'en',
    debug: false,
    interpolation: { escapeValue: false },
    resources: {
      en: { translation: en },
      vi: { translation: vi },
    },
  });

export const SUPPORTED_LANGUAGES = ['en', 'vi'] as const;
export type SupportedLanguage = (typeof SUPPORTED_LANGUAGES)[number];

export default i18n;
