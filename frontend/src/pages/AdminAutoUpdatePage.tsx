import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  AlertTriangle,
  ArrowLeft,
  GitMerge,
  GitPullRequest,
  Loader2,
  Save,
  ShieldAlert,
  Sparkles,
  Workflow,
} from 'lucide-react';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/ui/card';
import { Badge } from '../components/ui/badge';
import { Switch } from '../components/ui/switch';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '../components/ui/select';
import { Input } from '../components/ui/input';
import { LoadingState, ErrorState } from '../components/ui/states';
import { describeError } from '../services/auth';
import {
  PlatformDocTypePolicy,
  PlatformSettings,
  UpdatePlatformDocTypePolicyPayload,
  UpdatePlatformSettingsPayload,
  platformSettingsApi,
} from '../services/platformSettings';

const DRIFT_THRESHOLDS = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as const;
const MERGE_POLICIES = ['WARN', 'BLOCK'] as const;

const DOC_TYPE_LABELS: Record<string, string> = {
  MODULE_GUIDE: 'Module guide',
  API_REFERENCE: 'API reference',
  README: 'README',
  ARCHITECTURE: 'Architecture',
  ADR: 'ADR',
  CHANGELOG: 'Changelog',
  RUNBOOK: 'Runbook',
  DATA_DICTION: 'Data dictionary',
  TUTORIAL: 'Tutorial',
  CUSTOM: 'Custom',
};

/**
 * Admin console for platform-wide documentation governance defaults.
 *
 * <p>This page exposes two distinct concerns:
 * <ul>
 *   <li>Trigger + drift + merge defaults — copied into new workspaces
 *       on creation.</li>
 *   <li>Per-documentation-type workflow policy — whether generated
 *       docs of a given type are auto-applied or queued for review.</li>
 * </ul>
 */
export default function AdminAutoUpdatePage() {
  const { t } = useTranslation();
  const [settings, setSettings] = useState<PlatformSettings | null>(null);
  const [policies, setPolicies] = useState<PlatformDocTypePolicy[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [saving, setSaving] = useState<boolean>(false);
  const [policyBusy, setPolicyBusy] = useState<string | null>(null);

  // Local form state mirrors the server so we can compare dirty state.
  const [autoOnCommit, setAutoOnCommit] = useState<boolean>(false);
  const [autoOnPr, setAutoOnPr] = useState<boolean>(true);
  const [autoOnMerge, setAutoOnMerge] = useState<boolean>(false);
  const [driftThreshold, setDriftThreshold] = useState<string>('MEDIUM');
  const [requireApproval, setRequireApproval] = useState<boolean>(true);
  const [mergePolicy, setMergePolicy] = useState<string>('WARN');
  const [confidence, setConfidence] = useState<number>(0.70);

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const [s, p] = await Promise.all([
        platformSettingsApi.get(),
        platformSettingsApi.listDocTypePolicies(),
      ]);
      setSettings(s);
      setPolicies(p);
      // Seed local form state.
      setAutoOnCommit(s.defaultAutoUpdateOnCommit);
      setAutoOnPr(s.defaultAutoUpdateOnPr);
      setAutoOnMerge(s.defaultAutoUpdateOnMerge);
      setDriftThreshold(s.defaultDriftSeverityThreshold);
      setRequireApproval(s.defaultRequireManagerApproval);
      setMergePolicy(s.defaultMergePolicyCritical);
      setConfidence(s.defaultAiConfidenceThreshold);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void load(); }, []);

  async function saveSettings() {
    setSaving(true);
    setError(null);
    setInfo(null);
    try {
      const payload: UpdatePlatformSettingsPayload = {
        defaultAutoUpdateOnCommit: autoOnCommit,
        defaultAutoUpdateOnPr: autoOnPr,
        defaultAutoUpdateOnMerge: autoOnMerge,
        defaultDriftSeverityThreshold: driftThreshold,
        defaultRequireManagerApproval: requireApproval,
        defaultMergePolicyCritical: mergePolicy,
        defaultAiConfidenceThreshold: confidence,
      };
      const updated = await platformSettingsApi.update(payload);
      setSettings(updated);
      setInfo(t('adminAutoUpdate.savedSettings'));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSaving(false);
    }
  }

  async function changePolicy(p: PlatformDocTypePolicy,
                                workflow: 'AUTO_APPLY' | 'MANAGER_REVIEW') {
    setPolicyBusy(p.docType);
    setError(null);
    try {
      const payload: UpdatePlatformDocTypePolicyPayload = { workflow };
      const updated = await platformSettingsApi.upsertDocTypePolicy(p.docType, payload);
      setPolicies((prev) => prev.map((x) => (x.docType === updated.docType ? updated : x)));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setPolicyBusy(null);
    }
  }

  if (loading) return <LoadingState message={t('adminAutoUpdate.loading')} />;
  if (error && !settings) return <ErrorState message={error} />;
  if (!settings) return null;

  const isDirty = settings && (
    settings.defaultAutoUpdateOnCommit !== autoOnCommit
    || settings.defaultAutoUpdateOnPr !== autoOnPr
    || settings.defaultAutoUpdateOnMerge !== autoOnMerge
    || settings.defaultDriftSeverityThreshold !== driftThreshold
    || settings.defaultRequireManagerApproval !== requireApproval
    || settings.defaultMergePolicyCritical !== mergePolicy
    || Math.abs(settings.defaultAiConfidenceThreshold - confidence) > 0.001
  );

  return (
    <div className="space-y-6">
      <div>
        <Button variant="ghost" size="sm" asChild>
          <Link to="/admin">
            <ArrowLeft className="mr-1 h-4 w-4" /> {t('common.back')}
          </Link>
        </Button>
        <h1 className="mt-2 text-2xl font-bold tracking-tight flex items-center gap-2">
          <Workflow className="h-5 w-5" /> {t('adminAutoUpdate.title')}
        </h1>
        <p className="text-sm text-muted-foreground">{t('adminAutoUpdate.subtitle')}</p>
      </div>

      {error && <ErrorState message={error} />}
      {info && (
        <div className="rounded-md border border-success/30 bg-success/10 p-3 text-sm text-success">
          {info}
        </div>
      )}

      {/* Triggers + thresholds + AI defaults */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base flex items-center gap-2">
            <GitPullRequest className="h-4 w-4" /> {t('adminAutoUpdate.triggersTitle')}
          </CardTitle>
          <CardDescription>{t('adminAutoUpdate.triggersDesc')}</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid gap-3 sm:grid-cols-3">
            <label className="flex items-center gap-3 rounded-md border p-3 cursor-pointer">
              <Switch checked={autoOnCommit} onCheckedChange={setAutoOnCommit} />
              <div>
                <div className="text-sm font-medium">{t('adminAutoUpdate.onCommit')}</div>
                <p className="text-xs text-muted-foreground">{t('adminAutoUpdate.onCommitDesc')}</p>
              </div>
            </label>
            <label className="flex items-center gap-3 rounded-md border p-3 cursor-pointer">
              <Switch checked={autoOnPr} onCheckedChange={setAutoOnPr} />
              <div>
                <div className="text-sm font-medium">{t('adminAutoUpdate.onPr')}</div>
                <p className="text-xs text-muted-foreground">{t('adminAutoUpdate.onPrDesc')}</p>
              </div>
            </label>
            <label className="flex items-center gap-3 rounded-md border p-3 cursor-pointer">
              <Switch checked={autoOnMerge} onCheckedChange={setAutoOnMerge} />
              <div>
                <div className="text-sm font-medium">{t('adminAutoUpdate.onMerge')}</div>
                <p className="text-xs text-muted-foreground">{t('adminAutoUpdate.onMergeDesc')}</p>
              </div>
            </label>
          </div>

          <div className="grid gap-3 sm:grid-cols-3">
            <div className="space-y-1.5">
              <label className="text-xs font-medium">
                {t('adminAutoUpdate.driftThreshold')}
              </label>
              <Select value={driftThreshold} onValueChange={setDriftThreshold}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  {DRIFT_THRESHOLDS.map((d) => (
                    <SelectItem key={d} value={d}>{d}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5">
              <label className="text-xs font-medium">
                {t('adminAutoUpdate.mergePolicy')}
              </label>
              <Select value={mergePolicy} onValueChange={setMergePolicy}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  {MERGE_POLICIES.map((m) => (
                    <SelectItem key={m} value={m}>{m}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5">
              <label className="text-xs font-medium">
                {t('adminAutoUpdate.requireApproval')}
              </label>
              <div className="flex items-center gap-2 rounded-md border p-2 h-10">
                <Switch
                  checked={requireApproval}
                  onCheckedChange={setRequireApproval}
                />
                <span className="text-sm">{requireApproval ? t('common.yes') : t('common.no')}</span>
              </div>
            </div>
          </div>

          <div className="space-y-2 rounded-md border p-3">
            <div className="flex items-center gap-2">
              <Sparkles className="h-4 w-4" />
              <div className="text-sm font-medium">
                {t('adminAutoUpdate.confidenceThreshold')}: {confidence.toFixed(2)}
              </div>
            </div>
            <input
              type="range"
              min={0}
              max={1}
              step={0.05}
              value={confidence}
              onChange={(e) => setConfidence(Number(e.target.value))}
              className="w-full"
            />
            <div className="flex items-center gap-2">
              <span className="text-xs text-muted-foreground w-16">Value</span>
              <Input
                type="number"
                min={0}
                max={1}
                step={0.05}
                value={confidence}
                onChange={(e) => {
                  const v = Number(e.target.value);
                  if (!Number.isNaN(v)) setConfidence(Math.max(0, Math.min(1, v)));
                }}
                className="h-8 w-24"
              />
            </div>
            <p className="text-xs text-muted-foreground">
              {t('adminAutoUpdate.confidenceThresholdHelp')}
            </p>
          </div>

          <div className="flex justify-end">
            <Button onClick={() => void saveSettings()} disabled={saving || !isDirty}>
              {saving ? (
                <Loader2 className="mr-1 h-4 w-4 animate-spin" />
              ) : (
                <Save className="mr-1 h-4 w-4" />
              )}
              {t('adminAutoUpdate.saveDefaults')}
            </Button>
          </div>
        </CardContent>
      </Card>

      {/* Per-docType workflow policies */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base flex items-center gap-2">
            <GitMerge className="h-4 w-4" /> {t('adminAutoUpdate.docTypeTitle')}
          </CardTitle>
          <CardDescription>{t('adminAutoUpdate.docTypeDesc')}</CardDescription>
        </CardHeader>
        <CardContent className="p-0">
          <div className="divide-y">
            {policies.map((p) => {
              const busy = policyBusy === p.docType;
              return (
                <div key={p.docType} className="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
                  <div className="flex items-center gap-3 min-w-0 flex-1">
                    {p.workflow === 'AUTO_APPLY' ? (
                      <Sparkles className="h-4 w-4 text-success" />
                    ) : (
                      <ShieldAlert className="h-4 w-4 text-amber-500" />
                    )}
                    <div className="min-w-0">
                      <div className="text-sm font-medium">
                        {DOC_TYPE_LABELS[p.docType] ?? p.docType}
                      </div>
                      <code className="text-xs text-muted-foreground">{p.docType}</code>
                    </div>
                  </div>
                  <div className="flex items-center gap-2">
                    <Badge variant={p.workflow === 'AUTO_APPLY' ? 'success' : 'muted'}>
                      {p.workflow === 'AUTO_APPLY'
                        ? t('adminAutoUpdate.workflowAutoApply')
                        : t('adminAutoUpdate.workflowManagerReview')}
                    </Badge>
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={busy || p.workflow === 'AUTO_APPLY'}
                      onClick={() => void changePolicy(p, 'AUTO_APPLY')}
                    >
                      {t('adminAutoUpdate.workflowAutoApply')}
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={busy || p.workflow === 'MANAGER_REVIEW'}
                      onClick={() => void changePolicy(p, 'MANAGER_REVIEW')}
                    >
                      {t('adminAutoUpdate.workflowManagerReview')}
                    </Button>
                  </div>
                </div>
              );
            })}
            {policies.length === 0 && (
              <div className="p-4 text-sm text-muted-foreground flex items-center gap-2">
                <AlertTriangle className="h-4 w-4" />
                {t('adminAutoUpdate.noPolicies')}
              </div>
            )}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
