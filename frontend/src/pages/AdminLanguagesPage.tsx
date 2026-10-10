import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  ArrowLeft,
  Code2,
  Loader2,
  Save,
} from 'lucide-react';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/ui/card';
import { Textarea } from '../components/ui/textarea';
import { LoadingState, ErrorState } from '../components/ui/states';
import { Switch } from '../components/ui/switch';
import { describeError } from '../services/auth';
import {
  PlatformLanguage,
  UpdatePlatformLanguagePayload,
  platformLanguagesApi,
} from '../services/platformLanguages';

/**
 * Admin console for the platform-wide list of supported programming
 * languages.
 *
 * <p>Each row corresponds to a {@code PlatformLanguage} entry. Toggling
 * the row or editing the default prompt updates the platform default;
 * individual workspaces can still override either on their own
 * {@code /workspaces/{id}/languages} page.
 */
export default function AdminLanguagesPage() {
  const { t } = useTranslation();

  const [rows, setRows] = useState<PlatformLanguage[] | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [editPrompt, setEditPrompt] = useState<string>('');
  const [editName, setEditName] = useState<string>('');
  const [saving, setSaving] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const data = await platformLanguagesApi.list();
      setRows(data);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void load(); }, []);

  function openEditor(l: PlatformLanguage) {
    setEditing(l.languageCode);
    setEditPrompt(l.defaultPrompt ?? '');
    setEditName(l.languageName);
  }

  function closeEditor() {
    setEditing(null);
    setEditPrompt('');
    setEditName('');
  }

  async function saveLanguage(l: PlatformLanguage) {
    setSaving(l.languageCode);
    setError(null);
    try {
      const payload: UpdatePlatformLanguagePayload = {
        languageName: editName.trim() || l.languageName,
        enabled: l.enabled,
        defaultPrompt: editPrompt.trim() || null,
        sortOrder: l.sortOrder,
      };
      const updated = await platformLanguagesApi.update(l.languageCode, payload);
      setRows((prev) =>
        prev ? prev.map((r) => (r.languageCode === updated.languageCode ? updated : r)) : prev,
      );
      closeEditor();
      setInfo(t('adminLanguages.saved'));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSaving(null);
    }
  }

  async function toggleEnabled(l: PlatformLanguage, enabled: boolean) {
    setSaving(l.languageCode);
    setError(null);
    try {
      const updated = await platformLanguagesApi.update(l.languageCode, {
        languageName: l.languageName,
        enabled,
        defaultPrompt: l.defaultPrompt,
        sortOrder: l.sortOrder,
      });
      setRows((prev) =>
        prev ? prev.map((r) => (r.languageCode === updated.languageCode ? updated : r)) : prev,
      );
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSaving(null);
    }
  }

  if (loading) return <LoadingState message={t('adminLanguages.loading')} />;
  if (error && !rows) return <ErrorState message={error} />;
  if (!rows) return null;

  const enabledCount = rows.filter((r) => r.enabled).length;

  return (
    <div className="space-y-6">
      <div>
        <Button variant="ghost" size="sm" asChild>
          <Link to="/admin">
            <ArrowLeft className="mr-1 h-4 w-4" /> {t('common.back')}
          </Link>
        </Button>
        <h1 className="mt-2 text-2xl font-bold tracking-tight flex items-center gap-2">
          <Code2 className="h-5 w-5" /> {t('adminLanguages.title')}
        </h1>
        <p className="text-sm text-muted-foreground">{t('adminLanguages.subtitle')}</p>
      </div>

      {error && <ErrorState message={error} />}
      {info && (
        <div className="rounded-md border border-success/30 bg-success/10 p-3 text-sm text-success">
          {info}
        </div>
      )}

      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t('adminLanguages.catalogue')}</CardTitle>
          <CardDescription>
            {t('adminLanguages.summary', { total: rows.length, enabled: enabledCount })}
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="space-y-2">
            {rows.map((l) => {
              const isEditing = editing === l.languageCode;
              const isSaving = saving === l.languageCode;
              return (
                <div key={l.id} className="rounded-md border">
                  <div className="flex flex-wrap items-center justify-between gap-3 p-3">
                    <div className="flex items-center gap-3">
                      <Switch
                        checked={l.enabled}
                        onCheckedChange={(checked) => void toggleEnabled(l, checked)}
                        disabled={isSaving}
                      />
                      <div>
                        <div className="text-sm font-medium">{l.languageName}</div>
                        <code className="text-xs text-muted-foreground">{l.languageCode}</code>
                      </div>
                    </div>
                    <div className="flex flex-wrap items-center gap-2">
                      {l.defaultPrompt ? (
                        <span className="text-[11px] text-muted-foreground italic">
                          {t('adminLanguages.hasDefaultPrompt')}
                        </span>
                      ) : null}
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => (isEditing ? closeEditor() : openEditor(l))}
                        disabled={isSaving}
                      >
                        {isEditing ? t('common.cancel') : t('adminLanguages.edit')}
                      </Button>
                    </div>
                  </div>
                  {isEditing && (
                    <div className="border-t p-3 space-y-3">
                      <div className="space-y-1.5">
                        <label className="text-xs font-medium">
                          {t('adminLanguages.displayName')}
                        </label>
                        <input
                          type="text"
                          value={editName}
                          onChange={(e) => setEditName(e.target.value)}
                          className="w-full rounded-md border bg-background px-3 py-2 text-sm"
                          maxLength={80}
                        />
                      </div>
                      <div className="space-y-1.5">
                        <label className="text-xs font-medium">
                          {t('adminLanguages.defaultPrompt')}
                        </label>
                        <Textarea
                          value={editPrompt}
                          onChange={(e) => setEditPrompt(e.target.value)}
                          rows={3}
                          placeholder={t('adminLanguages.defaultPromptPlaceholder')}
                        />
                        <p className="text-xs text-muted-foreground">
                          {t('adminLanguages.defaultPromptHelp')}
                        </p>
                      </div>
                      <div className="flex gap-2">
                        <Button
                          size="sm"
                          onClick={() => void saveLanguage(l)}
                          disabled={isSaving}
                        >
                          {isSaving ? (
                            <Loader2 className="mr-1 h-3 w-3 animate-spin" />
                          ) : (
                            <Save className="mr-1 h-3 w-3" />
                          )}
                          {t('common.save')}
                        </Button>
                        <Button size="sm" variant="ghost" onClick={closeEditor}>
                          {t('common.cancel')}
                        </Button>
                      </div>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
