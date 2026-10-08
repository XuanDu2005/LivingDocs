import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ArrowLeft, Code2, Save, Loader2 } from 'lucide-react';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/ui/card';
import { Textarea } from '../components/ui/textarea';
import { LoadingState, ErrorState } from '../components/ui/states';
import { Switch } from '../components/ui/switch';
import { describeError } from '../services/auth';
import {
  SUPPORTED_LANGUAGES,
  UpdateLanguagePayload,
  WorkspaceLanguage,
  workspaceLanguagesApi,
} from '../services/workspaceLanguages';

export default function WorkspaceLanguagesPage() {
  const { workspaceId } = useParams<{ workspaceId: string }>();
  const { t } = useTranslation();
  const [languages, setLanguages] = useState<WorkspaceLanguage[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [editPrompt, setEditPrompt] = useState<string>('');
  const [saving, setSaving] = useState<boolean>(false);

  async function load() {
    if (!workspaceId) return;
    setLoading(true);
    setError(null);
    try {
      const langs = await workspaceLanguagesApi.list(workspaceId);
      setLanguages(langs);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, [workspaceId]);

  function getLanguageConfig(code: string) {
    return languages.find((l) => l.languageCode === code);
  }

  function getEffectiveEnabled(code: string) {
    const cfg = getLanguageConfig(code);
    return cfg?.enabled ?? false;
  }

  function getEffectivePrompt(code: string) {
    const cfg = getLanguageConfig(code);
    return cfg?.customPrompt ?? '';
  }

  async function toggleLanguage(code: string, name: string, enabled: boolean) {
    if (!workspaceId) return;
    const existing = getLanguageConfig(code);
    const payload: UpdateLanguagePayload = {
      languageCode: code,
      languageName: name,
      enabled,
      customPrompt: existing?.customPrompt ?? null,
    };
    try {
      const updated = await workspaceLanguagesApi.update(workspaceId, payload);
      setLanguages((prev) => {
        const filtered = prev.filter((l) => l.languageCode !== code);
        return [...filtered, updated];
      });
    } catch (err) {
      setError(describeError(err));
    }
  }

  async function savePrompt(code: string, name: string) {
    if (!workspaceId) return;
    setSaving(true);
    try {
      const existing = getLanguageConfig(code);
      const payload: UpdateLanguagePayload = {
        languageCode: code,
        languageName: name,
        enabled: existing?.enabled ?? true,
        customPrompt: editPrompt.trim() || null,
      };
      const updated = await workspaceLanguagesApi.update(workspaceId, payload);
      setLanguages((prev) => {
        const filtered = prev.filter((l) => l.languageCode !== code);
        return [...filtered, updated];
      });
      setEditing(null);
      setEditPrompt('');
      setInfo('Custom prompt saved.');
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSaving(false);
    }
  }

  function openPromptEditor(code: string) {
    setEditing(code);
    setEditPrompt(getEffectivePrompt(code));
  }

  if (!workspaceId) return null;
  if (loading) return <LoadingState message="Loading languages..." />;

  return (
    <div className="space-y-6">
      <Button variant="ghost" size="sm" asChild>
        <Link to={`/workspaces/${workspaceId}`}>
          <ArrowLeft className="mr-1 h-4 w-4" /> {t('common.back')}
        </Link>
      </Button>

      <div>
        <h1 className="text-2xl font-bold tracking-tight flex items-center gap-2">
          <Code2 className="h-5 w-5" /> Supported Languages
        </h1>
        <p className="text-sm text-muted-foreground">
          Enable programming languages for AI code analysis and documentation generation.
          Add custom prompts per language to specialize the AI output.
        </p>
      </div>

      {error && <ErrorState message={error} />}
      {info && (
        <div className="rounded-md border border-success/30 bg-success/10 p-3 text-sm text-success">
          {info}
        </div>
      )}

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Programming Languages</CardTitle>
          <CardDescription>
            {SUPPORTED_LANGUAGES.length} languages available
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="space-y-2">
            {SUPPORTED_LANGUAGES.map((lang) => {
              const enabled = getEffectiveEnabled(lang.code);
              const isEditing = editing === lang.code;
              return (
                <div key={lang.code} className="rounded-md border">
                  <div className="flex items-center justify-between p-3">
                    <div className="flex items-center gap-3">
                      <Switch
                        checked={enabled}
                        onCheckedChange={(checked) =>
                          void toggleLanguage(lang.code, lang.name, checked)
                        }
                      />
                      <div>
                        <div className="text-sm font-medium">{lang.name}</div>
                        <code className="text-xs text-muted-foreground">{lang.code}</code>
                      </div>
                    </div>
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() =>
                        isEditing ? setEditing(null) : openPromptEditor(lang.code)
                      }
                    >
                      {isEditing ? 'Cancel' : getEffectivePrompt(lang.code) ? 'Edit prompt' : 'Add prompt'}
                    </Button>
                  </div>
                  {isEditing && (
                    <div className="border-t p-3 space-y-2">
                      <label className="text-xs font-medium">Custom prompt</label>
                      <Textarea
                        value={editPrompt}
                        onChange={(e) => setEditPrompt(e.target.value)}
                        rows={3}
                        placeholder={`Custom instructions for ${lang.name} (e.g. "Focus on JSDoc comments and TypeScript types")`}
                      />
                      <div className="flex gap-2">
                        <Button
                          size="sm"
                          onClick={() => void savePrompt(lang.code, lang.name)}
                          disabled={saving}
                        >
                          {saving ? (
                            <Loader2 className="mr-1 h-3 w-3 animate-spin" />
                          ) : (
                            <Save className="mr-1 h-3 w-3" />
                          )}
                          Save prompt
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => {
                            setEditing(null);
                            setEditPrompt('');
                          }}
                        >
                          Cancel
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
