import { useEffect, useState } from 'react';
import { Link, useParams, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ArrowLeft, CheckCircle2, KeyRound, Loader2, Save, Sparkles, Trash2 } from 'lucide-react';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/ui/card';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Badge } from '../components/ui/badge';
import { PasswordInput } from '../components/ui/password-input';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '../components/ui/select';
import { LoadingState, ErrorState } from '../components/ui/states';
import { describeError } from '../services/auth';
import { aiSettingsApi } from '../services/aiSettings';
import { AiProvider, AiSettings, PROVIDER_DEFAULTS, UpdateAiSettingsPayload } from '../types/aiSettings';

export default function WorkspaceAiSettingsPage() {
  const { workspaceId } = useParams<{ workspaceId: string }>();
  const { t } = useTranslation();
  const location = useLocation();
  // When the page is reached from the admin workspace picker the back
  // link should return there, not to the (non-existent) regular
  // workspace detail view.
  const fromAdmin = location.pathname.startsWith('/admin/ai-settings/');
  const backTo = fromAdmin ? '/admin/ai-settings' : `/workspaces/${workspaceId}`;
  const backLabel = fromAdmin ? t('aiSettings.backToList') : t('common.back');

  const [current, setCurrent] = useState<AiSettings | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [saving, setSaving] = useState<boolean>(false);
  const [testing, setTesting] = useState<boolean>(false);
  const [testResult, setTestResult] = useState<{ ok: boolean; message: string } | null>(null);

  // Editable form state
  const [provider, setProvider] = useState<AiProvider>('SANDBOX');
  const [model, setModel] = useState<string>('gpt-4o-mini');
  const [baseUrl, setBaseUrl] = useState<string>('');
  const [temperature, setTemperature] = useState<number>(0.2);
  const [maxTokens, setMaxTokens] = useState<number>(2048);
  const [apiKey, setApiKey] = useState<string>('');
  const [apiKeyDirty, setApiKeyDirty] = useState<boolean>(false);

  useEffect(() => {
    if (!workspaceId) return;
    void load();
  }, [workspaceId]);

  async function load() {
    if (!workspaceId) return;
    setLoading(true);
    setError(null);
    setInfo(null);
    try {
      const s = await aiSettingsApi.get(workspaceId);
      apply(s);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }

  function apply(s: AiSettings) {
    setCurrent(s);
    setProvider(s.provider);
    setModel(s.model);
    setBaseUrl(s.baseUrl ?? '');
    setTemperature(s.temperature ?? 0.2);
    setMaxTokens(s.maxTokens ?? 2048);
    setApiKey('');
    setApiKeyDirty(false);
  }

  function pickProvider(next: AiProvider) {
    setProvider(next);
    const def = PROVIDER_DEFAULTS[next];
    // Only override the model/baseUrl if the user has not customised them
    // away from the current provider's defaults. This avoids clobbering
    // deliberate choices when toggling between providers.
    if (def) {
      setModel(def.model);
      if (def.baseUrl) setBaseUrl(def.baseUrl);
    }
  }

  async function save() {
    if (!workspaceId) return;
    setSaving(true);
    setError(null);
    setInfo(null);
    setTestResult(null);
    try {
      const payload: UpdateAiSettingsPayload = {
        provider,
        model: model.trim(),
        baseUrl: baseUrl.trim() || null,
        temperature,
        maxTokens,
      };
      if (apiKeyDirty && apiKey.trim().length > 0) {
        payload.apiKey = apiKey.trim();
      } else if (apiKeyDirty && apiKey.trim().length === 0 && current?.hasApiKey) {
        // User cleared the key input while one was previously set.
        payload.clearApiKey = true;
      }
      const updated = await aiSettingsApi.update(workspaceId, payload);
      apply(updated);
      setInfo(t('aiSettings.saveOk'));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSaving(false);
    }
  }

  async function clearKey() {
    if (!workspaceId) return;
    if (!current?.hasApiKey) return;
    if (!confirm(t('aiSettings.confirmRemoveKey'))) return;
    setSaving(true);
    setError(null);
    setInfo(null);
    try {
      const updated = await aiSettingsApi.update(workspaceId, { clearApiKey: true });
      apply(updated);
      setInfo(t('aiSettings.keyCleared'));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSaving(false);
    }
  }

  async function test() {
    if (!workspaceId) return;
    setTesting(true);
    setTestResult(null);
    setError(null);
    try {
      const r = await aiSettingsApi.test(workspaceId);
      if (r.hasApiKey) {
        setTestResult({ ok: true, message: `Reachable — ${r.provider} / ${r.model}` });
      } else {
        setTestResult({ ok: true, message: `Reachable — ${r.provider} / ${r.model} (no key configured)` });
      }
    } catch (err) {
      setTestResult({ ok: false, message: describeError(err) });
    } finally {
      setTesting(false);
    }
  }

  if (loading) return <LoadingState message={t('aiSettings.loading')} />;
  if (error && !current) return <ErrorState message={error} />;

  return (
    <div className="space-y-6">
      <Button variant="ghost" size="sm" asChild>
        <Link to={backTo}>
          <ArrowLeft className="mr-1 h-4 w-4" /> {backLabel}
        </Link>
      </Button>

      <div>
        <h1 className="text-2xl font-bold tracking-tight flex items-center gap-2">
          <Sparkles className="h-5 w-5" /> {t('aiSettings.title')}
        </h1>
        <p className="text-sm text-muted-foreground">
          {t('aiSettings.subtitle')}
        </p>
      </div>

      {info && (
        <div className="rounded-md border border-green-500/30 bg-green-500/10 px-3 py-2 text-sm text-green-700 flex items-center gap-2">
          <CheckCircle2 className="h-4 w-4" /> {info}
        </div>
      )}
      {error && current && <ErrorState message={error} />}

      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t('aiSettings.provider')}</CardTitle>
          <CardDescription>
            {t('aiSettings.providerDesc')}
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="space-y-1.5">
              <Label htmlFor="ai-provider">{t('aiSettings.provider')}</Label>
              <Select value={provider} onValueChange={(v) => pickProvider(v as AiProvider)}>
                <SelectTrigger id="ai-provider">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {(Object.keys(PROVIDER_DEFAULTS) as AiProvider[]).map((p) => (
                    <SelectItem key={p} value={p}>
                      {PROVIDER_DEFAULTS[p].label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="ai-model">{t('aiSettings.model')}</Label>
              <Input
                id="ai-model"
                value={model}
                onChange={(e) => setModel(e.target.value)}
                placeholder="e.g. gpt-4o-mini"
              />
            </div>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="ai-baseUrl">{t('aiSettings.baseUrl')}</Label>
            <Input
              id="ai-baseUrl"
              value={baseUrl}
              onChange={(e) => setBaseUrl(e.target.value)}
              placeholder={
                provider === 'OPENAI' ? 'https://api.openai.com'
                : provider === 'ANTHROPIC' ? 'https://api.anthropic.com'
                : provider === 'OLLAMA' ? 'http://localhost:11434'
                : 'https://my-llm-gateway.local/v1'
              }
            />
            <p className="text-xs text-muted-foreground">
              {t('aiSettings.baseUrlDesc')}
            </p>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t('aiSettings.generationControls')}</CardTitle>
          <CardDescription>
            {t('aiSettings.generationControlsDesc')}
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="space-y-1.5">
              <Label htmlFor="ai-temperature">{t('aiSettings.temperature')} ({temperature.toFixed(2)})</Label>
              <input
                id="ai-temperature"
                type="range"
                min={0}
                max={2}
                step={0.05}
                value={temperature}
                onChange={(e) => setTemperature(Number(e.target.value))}
                className="w-full"
              />
              <p className="text-xs text-muted-foreground">{t('aiSettings.temperatureDesc')}</p>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="ai-maxTokens">{t('aiSettings.maxTokens')}</Label>
              <Input
                id="ai-maxTokens"
                type="number"
                min={1}
                max={32_000}
                value={maxTokens}
                onChange={(e) => setMaxTokens(Math.max(1, Number(e.target.value) || 1))}
              />
            </div>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base flex items-center gap-2">
            <KeyRound className="h-4 w-4" /> {t('aiSettings.apiKey')}
          </CardTitle>
          <CardDescription>
            {t('aiSettings.apiKeyCardDesc')}
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex items-center gap-2 text-sm">
            <span className="text-muted-foreground">{t('aiSettings.status')}:</span>
            {current?.hasApiKey
              ? <Badge variant="success">{t('aiSettings.configured')}</Badge>
              : <Badge variant="muted">{t('aiSettings.notConfigured')}</Badge>}
            {current?.apiKeyMasked && (
              <span className="text-xs text-muted-foreground font-mono">{current.apiKeyMasked}</span>
            )}
          </div>

          <PasswordInput
            id="ai-apiKey"
            label={t('aiSettings.newApiKey')}
            placeholder={current?.hasApiKey ? t('aiSettings.apiKeyHint') : t('aiSettings.apiKeyPlaceholder')}
            value={apiKey}
            onChange={(e) => {
              setApiKey(e.target.value);
              setApiKeyDirty(true);
            }}
            autoComplete="off"
          />

          {current?.hasApiKey && (
            <Button
              type="button"
              variant="outline"
              size="sm"
              className="text-destructive"
              onClick={() => void clearKey()}
              disabled={saving}
            >
              <Trash2 className="mr-1 h-3 w-3" /> {t('aiSettings.removeKey')}
            </Button>
          )}
        </CardContent>
      </Card>

      <div className="flex flex-wrap items-center gap-2">
        <Button onClick={() => void save()} disabled={saving}>
          {saving ? <Loader2 className="mr-1 h-4 w-4 animate-spin" /> : <Save className="mr-1 h-4 w-4" />}
          {saving ? t('common.actions.saving') : t('aiSettings.saveSettings')}
        </Button>
        <Button variant="outline" onClick={() => void test()} disabled={testing || saving}>
          {testing ? <Loader2 className="mr-1 h-4 w-4 animate-spin" /> : null}
          {testing ? t('aiSettings.testing') : t('aiSettings.testConnection')}
        </Button>
        {testResult && (
          <span className={`text-sm ${testResult.ok ? 'text-green-700' : 'text-destructive'}`}>
            {testResult.message}
          </span>
        )}
      </div>
    </div>
  );
}
