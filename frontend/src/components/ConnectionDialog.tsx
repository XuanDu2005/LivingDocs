import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Loader2, Save } from 'lucide-react';
import { Button } from './ui/button';
import { Input } from './ui/input';
import { Label } from './ui/label';
import { PasswordInput } from './ui/password-input';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter,
  DialogHeader, DialogTitle,
} from './ui/dialog';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from './ui/select';
import { describeError } from '../services/auth';
import { integrationsApi } from '../services/integrations';
import {
  ConnectIntegrationPayload,
  Connection,
  IntegrationProvider,
  PROVIDER_DESCRIPTIONS,
  PROVIDER_LABELS,
} from '../types/integrations';

export const ALL_PROVIDERS: IntegrationProvider[] = ['GITHUB', 'GITLAB', 'JIRA', 'SLACK'];

export interface ConnectionDialogProps {
  open: boolean;
  /**
   * When provided, the dialog is locked to a single provider (used by
   * the workspace page where the card already picked a provider). When
   * `null` the dialog shows a provider picker.
   */
  provider: IntegrationProvider | null;
  /** Required when `scope = "workspace"`; ignored otherwise. */
  workspaceId?: string;
  /** Admin = `/admin/integrations`; workspace = `/workspaces/{id}/integrations`. */
  scope: 'admin' | 'workspace';
  /** Pass an existing connection to switch the dialog into "edit" mode. */
  existing: Connection | null;
  onClose: () => void;
  onSaved: () => Promise<void> | void;
}

/**
 * Shared create / edit dialog for an integration connection.
 *
 * <p>Both the admin console and the per-workspace integrations page
 * need the exact same form, so we keep it here. The submit handler
 * picks the correct API based on {@link ConnectionDialogProps.scope}.
 */
export function ConnectionDialog({
  open, provider, workspaceId, scope, existing, onClose, onSaved,
}: ConnectionDialogProps) {
  const { t } = useTranslation();
  const [provider2, setProvider2] = useState<IntegrationProvider>(provider ?? 'GITHUB');
  const [displayName, setDisplayName] = useState<string>('');
  const [externalAccount, setExternalAccount] = useState<string>('');
  const [baseUrl, setBaseUrl] = useState<string>('');
  const [scopes, setScopes] = useState<string>('');
  const [accessToken, setAccessToken] = useState<string>('');
  const [refreshToken, setRefreshToken] = useState<string>('');
  const [webhookSecret, setWebhookSecret] = useState<string>('');
  const [defaultChannel, setDefaultChannel] = useState<string>('');

  const [saving, setSaving] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    const p = provider ?? 'GITHUB';
    setProvider2(p);
    setDisplayName(existing?.displayName ?? '');
    setExternalAccount(existing?.externalAccount ?? '');
    setBaseUrl(existing?.baseUrl ?? '');
    setScopes(existing?.scopes ?? '');
    setAccessToken('');
    setRefreshToken('');
    setWebhookSecret('');
    setDefaultChannel(existing?.defaultChannel ?? '');
    setError(null);
  }, [open, provider, existing]);

  if (!open) return null;
  if (scope === 'workspace' && !workspaceId) return null;

  async function onSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (saving) return;
    setSaving(true);
    setError(null);
    try {
      const payload: ConnectIntegrationPayload = {
        provider: provider2,
        workspaceId: scope === 'workspace' ? workspaceId ?? null : null,
        displayName: displayName.trim(),
        externalAccount: externalAccount.trim(),
        baseUrl: baseUrl.trim() || null,
        scopes: scopes.trim() || null,
        accessToken: accessToken.trim() || null,
        refreshToken: refreshToken.trim() || null,
        webhookSecret: webhookSecret.trim() || null,
        defaultChannel: defaultChannel.trim() || null,
      };
      if (existing && scope === 'admin') {
        await integrationsApi.updateAdminConnection(existing.id, payload);
      } else if (scope === 'admin') {
        await integrationsApi.createAdminConnection(payload);
      } else if (workspaceId) {
        await integrationsApi.createWorkspaceConnection(workspaceId, payload);
      }
      await onSaved();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSaving(false);
    }
  }

  const effectiveProvider = provider ?? provider2;
  const showWebhookSecret =
    effectiveProvider === 'GITHUB' || effectiveProvider === 'GITLAB' ||
    effectiveProvider === 'JIRA' || effectiveProvider === 'SLACK';
  const showDefaultChannel = effectiveProvider === 'SLACK';

  const dialogTitle = existing
    ? t('integrations.editTitle')
    : t('integrations.addTitle', {
        provider: provider ? PROVIDER_LABELS[provider] : '',
      });
  const dialogDescription = provider
    ? PROVIDER_DESCRIPTIONS[provider]
    : t('integrations.addPickProvider');

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent className="max-w-xl">
        <DialogHeader>
          <DialogTitle>{dialogTitle}</DialogTitle>
          <DialogDescription>{dialogDescription}</DialogDescription>
        </DialogHeader>
        <form onSubmit={onSubmit} className="space-y-3">
          {!provider && (
            <div className="space-y-1.5">
              <Label>{t('integrations.provider')}</Label>
              <Select
                value={provider2}
                onValueChange={(v) => setProvider2(v as IntegrationProvider)}
              >
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  {ALL_PROVIDERS.map((p) => (
                    <SelectItem key={p} value={p}>{PROVIDER_LABELS[p]}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          )}

          <div className="space-y-1.5">
            <Label htmlFor="int-display">{t('integrations.displayName')}</Label>
            <Input id="int-display" required value={displayName}
                   onChange={(e) => setDisplayName(e.target.value)} />
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="int-account">{t('integrations.externalAccount')}</Label>
            <Input id="int-account" required value={externalAccount}
                   onChange={(e) => setExternalAccount(e.target.value)}
                   placeholder={
                     effectiveProvider === 'GITHUB' ? 'octocat'
                     : effectiveProvider === 'GITLAB' ? 'my-team'
                     : effectiveProvider === 'JIRA'   ? 'acme.atlassian.net'
                     : 'T1234567'}
            />
          </div>

          {(effectiveProvider === 'GITLAB' || effectiveProvider === 'JIRA') && (
            <div className="space-y-1.5">
              <Label htmlFor="int-base">{t('integrations.baseUrl')}</Label>
              <Input id="int-base" value={baseUrl}
                     onChange={(e) => setBaseUrl(e.target.value)}
                     placeholder={effectiveProvider === 'JIRA'
                       ? 'https://acme.atlassian.net'
                       : 'https://gitlab.com'} />
            </div>
          )}

          <div className="space-y-1.5">
            <Label htmlFor="int-scopes">{t('integrations.scopes')}</Label>
            <Input id="int-scopes" value={scopes}
                   onChange={(e) => setScopes(e.target.value)}
                   placeholder="repo, read:org" />
          </div>

          <PasswordInput
            id="int-token"
            label={existing
              ? t('integrations.tokenReplace')
              : t('integrations.accessToken')}
            placeholder={existing?.hasAccessToken ? t('integrations.tokenKeep') : ''}
            value={accessToken}
            onChange={(e) => setAccessToken(e.target.value)}
            autoComplete="off"
          />

          {showDefaultChannel && (
            <div className="space-y-1.5">
              <Label htmlFor="int-channel">{t('integrations.defaultChannel')}</Label>
              <Input id="int-channel" value={defaultChannel}
                     onChange={(e) => setDefaultChannel(e.target.value)}
                     placeholder="#livingdocs" />
            </div>
          )}

          {showWebhookSecret && (
            <PasswordInput
              id="int-secret"
              label={existing
                ? t('integrations.webhookSecretReplace')
                : t('integrations.webhookSecret')}
              placeholder={existing?.hasWebhookSecret ? t('integrations.tokenKeep') : ''}
              value={webhookSecret}
              onChange={(e) => setWebhookSecret(e.target.value)}
              autoComplete="off"
            />
          )}

          {provider2 !== 'GITHUB' && provider2 !== 'SLACK' && (
            <div className="space-y-1.5">
              <Label htmlFor="int-refresh">{t('integrations.refreshToken')}</Label>
              <Input id="int-refresh" type="password" value={refreshToken}
                     onChange={(e) => setRefreshToken(e.target.value)} autoComplete="off" />
            </div>
          )}

          {error && (
            <div className="rounded border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive">
              {error}
            </div>
          )}

          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>
              {t('common.actions.cancel')}
            </Button>
            <Button type="submit" disabled={saving}>
              {saving ? <Loader2 className="mr-1 h-4 w-4 animate-spin" /> : <Save className="mr-1 h-4 w-4" />}
              {saving ? t('common.actions.saving') : t('common.actions.save')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
