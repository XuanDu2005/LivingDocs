import { Navigate, useLocation, useParams, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { BellRing, Code2, GitMerge, ShieldCheck, Sparkles } from 'lucide-react';
import { PageTitle } from '../../components/PageTitle';
import { Tabs, TabsList, TabsTrigger, TabsContent } from '../../components/ui/tabs';
import { ErrorState } from '../../components/ui/states';
import { usePermission } from '../../hooks/usePermission';
import WorkspaceAiSettingsPage from '../WorkspaceAiSettingsPage';
import WorkspaceLanguagesPage from '../WorkspaceLanguagesPage';
import WorkspaceNotificationPoliciesPage from '../WorkspaceNotificationPoliciesPage';
import WorkspaceDocTypePolicyPanel from '../../components/WorkspaceDocTypePolicyPanel';
import MergeGatePanel from '../../components/MergeGatePanel';

type TabKey = 'ai' | 'languages' | 'notifications' | 'docTypes' | 'mergeGate';

const TAB_KEYS: TabKey[] = ['ai', 'languages', 'notifications', 'docTypes', 'mergeGate'];

/**
 * Which permission unlocks which tab.
 *
 * <p>The AI tab exposes the provider, the base URL and the API-key
 * mask, so it needs `AI_CONFIG_READ`. The notification tab shows the
 * resolved policies and delivery channels, so it needs
 * `NOTIFICATION_POLICY_VIEW`. The doc-type tab shows the workflow the
 * runtime enforces per documentation type, and the merge-gate tab
 * shows the policy the CI gate enforces, so both need
 * `GOVERNANCE_VIEW`. The languages tab has no capability-scoped data,
 * so it stays open to every workspace member.
 */
const TAB_PERMISSIONS: Record<TabKey, string[]> = {
  ai: ['AI_CONFIG_READ'],
  languages: [],
  notifications: ['NOTIFICATION_POLICY_VIEW'],
  docTypes: ['GOVERNANCE_VIEW'],
  mergeGate: ['GOVERNANCE_VIEW'],
};

/**
 * Consolidated workspace settings page. Hosts 5 tabs (AI, Languages,
 * Notification policies, Doc-type policies, Merge gate) so users no
 * longer have to dig through the sidebar to find each one. The old URLs
 * (including /workspaces/:id/ai-settings etc.) are redirected to this
 * page with the right ?tab=… so existing bookmarks keep working.
 */
export default function WorkspaceSettingsPage() {
  const { workspaceId } = useParams<{ workspaceId: string }>();
  const { t } = useTranslation();
  const [searchParams, setSearchParams] = useSearchParams();

  const canViewAi = usePermission('AI_CONFIG_READ');
  const canViewNotifications = usePermission('NOTIFICATION_POLICY_VIEW');
  const canViewGovernance = usePermission('GOVERNANCE_VIEW');
  const canManageGovernance = usePermission('GOVERNANCE_MANAGE');

  if (!workspaceId) {
    return <ErrorState message={t('common.missingIds', 'Missing identifiers')} />;
  }

  const availableTabs = TAB_KEYS.filter((key) => {
    const required = TAB_PERMISSIONS[key];
    if (required.length === 0) return true;
    if (key === 'ai') return canViewAi;
    if (key === 'notifications') return canViewNotifications;
    if (key === 'docTypes') return canViewGovernance;
    if (key === 'mergeGate') return canViewGovernance;
    return true;
  });

  const requested = parseTab(searchParams.get('tab'));
  // Fall back to the first tab the user is actually allowed to see, so
  // a direct link to a hidden tab does not render an empty page.
  const tab = availableTabs.includes(requested) ? requested : availableTabs[0] ?? 'languages';

  function setTab(next: TabKey) {
    const sp = new URLSearchParams(searchParams);
    sp.set('tab', next);
    setSearchParams(sp, { replace: true });
  }

  return (
    <div className="space-y-6">
      <PageTitle
        title={t('workspaceSettings.title', 'Workspace settings')}
        subtitle={t('workspaceSettings.subtitle', 'AI provider, languages, and notification policy for this workspace.')}
      />
      <Tabs value={tab} onValueChange={(v) => setTab(v as TabKey)}>
        <TabsList>
          {availableTabs.includes('ai') && (
            <TabsTrigger value="ai">
              <Sparkles className="mr-1 h-4 w-4" /> {t('workspaceSettings.tabAi', 'AI')}
            </TabsTrigger>
          )}
          {availableTabs.includes('languages') && (
            <TabsTrigger value="languages">
              <Code2 className="mr-1 h-4 w-4" /> {t('workspaceSettings.tabLanguages', 'Languages')}
            </TabsTrigger>
          )}
          {availableTabs.includes('notifications') && (
            <TabsTrigger value="notifications">
              <BellRing className="mr-1 h-4 w-4" /> {t('workspaceSettings.tabNotifications', 'Notifications')}
            </TabsTrigger>
          )}
          {availableTabs.includes('docTypes') && (
            <TabsTrigger value="docTypes">
              <ShieldCheck className="mr-1 h-4 w-4" /> {t('workspaceSettings.tabDocTypes', 'Doc types')}
            </TabsTrigger>
          )}
          {availableTabs.includes('mergeGate') && (
            <TabsTrigger value="mergeGate">
              <GitMerge className="mr-1 h-4 w-4" /> {t('workspaceSettings.tabMergeGate', 'Merge gate')}
            </TabsTrigger>
          )}
        </TabsList>
        {availableTabs.includes('ai') && (
          <TabsContent value="ai">
            <WorkspaceAiSettingsPage />
          </TabsContent>
        )}
        {availableTabs.includes('languages') && (
          <TabsContent value="languages">
            <WorkspaceLanguagesPage />
          </TabsContent>
        )}
        {availableTabs.includes('notifications') && (
          <TabsContent value="notifications">
            <WorkspaceNotificationPoliciesPage />
          </TabsContent>
        )}
        {availableTabs.includes('docTypes') && (
          <TabsContent value="docTypes">
            <WorkspaceDocTypePolicyPanel
              workspaceId={workspaceId}
              hasGovernanceManage={canManageGovernance}
            />
          </TabsContent>
        )}
        {availableTabs.includes('mergeGate') && (
          <TabsContent value="mergeGate">
            <MergeGatePanel
              workspaceId={workspaceId}
              canManage={canManageGovernance}
              canView={canViewGovernance}
            />
          </TabsContent>
        )}
      </Tabs>
    </div>
  );
}

function parseTab(value: string | null): TabKey {
  if (value && (TAB_KEYS as string[]).includes(value)) return value as TabKey;
  return 'ai';
}

/**
 * Helper component used by AppRoutes to redirect legacy URLs to the new
 * consolidated page. The ?tab=… parameter is chosen from the source path
 * so users land on the right tab without thinking about it.
 */
export function LegacyWorkspaceSettingsRedirect() {
  const { workspaceId } = useParams<{ workspaceId: string }>();
  const location = useLocation();
  if (!workspaceId) {
    return <Navigate to="/workspaces" replace />;
  }
  let tab: TabKey = 'ai';
  if (location.pathname.includes('languages')) tab = 'languages';
  else if (location.pathname.includes('notification-policies')) tab = 'notifications';
  else if (location.pathname.includes('doc-type-policies')) tab = 'docTypes';
  return <Navigate to={`/workspaces/${workspaceId}/settings?tab=${tab}`} replace />;
}
