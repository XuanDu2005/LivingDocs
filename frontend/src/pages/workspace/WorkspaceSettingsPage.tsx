import { Navigate, useLocation, useParams, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { BellRing, Code2, Sparkles } from 'lucide-react';
import { PageTitle } from '../../components/PageTitle';
import { Tabs, TabsList, TabsTrigger, TabsContent } from '../../components/ui/tabs';
import { ErrorState } from '../../components/ui/states';
import { usePermission } from '../../hooks/usePermission';
import WorkspaceAiSettingsPage from '../WorkspaceAiSettingsPage';
import WorkspaceLanguagesPage from '../WorkspaceLanguagesPage';
import WorkspaceNotificationPoliciesPage from '../WorkspaceNotificationPoliciesPage';

type TabKey = 'ai' | 'languages' | 'notifications';

const TAB_KEYS: TabKey[] = ['ai', 'languages', 'notifications'];

/**
 * Which permission unlocks which tab.
 *
 * <p>The AI tab exposes the provider, the base URL and the API-key
 * mask, so it needs `AI_CONFIG_READ`. The notification tab shows the
 * resolved policies and delivery channels, so it needs
 * `NOTIFICATION_POLICY_VIEW`. The languages tab has no
 * capability-scoped data, so it stays open to every workspace member.
 */
const TAB_PERMISSIONS: Record<TabKey, string[]> = {
  ai: ['AI_CONFIG_READ'],
  languages: [],
  notifications: ['NOTIFICATION_POLICY_VIEW'],
};

/**
 * Consolidated workspace settings page. Hosts 3 tabs (AI, Languages,
 * Notification policies) so users no longer have to dig through the
 * sidebar to find each one. The 4 old URLs (including
 * /workspaces/:id/ai-settings etc.) are redirected to this page with
 * the right ?tab=… so existing bookmarks keep working.
 */
export default function WorkspaceSettingsPage() {
  const { workspaceId } = useParams<{ workspaceId: string }>();
  const { t } = useTranslation();
  const [searchParams, setSearchParams] = useSearchParams();

  const canViewAi = usePermission('AI_CONFIG_READ');
  const canViewNotifications = usePermission('NOTIFICATION_POLICY_VIEW');

  if (!workspaceId) {
    return <ErrorState message={t('common.missingIds', 'Missing identifiers')} />;
  }

  const availableTabs = TAB_KEYS.filter((key) => {
    const required = TAB_PERMISSIONS[key];
    if (required.length === 0) return true;
    if (key === 'ai') return canViewAi;
    if (key === 'notifications') return canViewNotifications;
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
  return <Navigate to={`/workspaces/${workspaceId}/settings?tab=${tab}`} replace />;
}
