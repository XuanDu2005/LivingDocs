import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ArrowLeft, Sparkles } from 'lucide-react';
import { Button } from '../components/ui/button';
import { Card, CardContent } from '../components/ui/card';
import { LoadingState, ErrorState } from '../components/ui/states';
import { describeError } from '../services/auth';
import { listWorkspaces, Workspace } from '../services/workspaces';
import WorkspaceAiSettingsPage from './WorkspaceAiSettingsPage';

/**
 * Admin-level AI settings entry. Admins can choose which workspace's
 * AI configuration to view or edit from a single page; once a workspace
 * is picked the page reuses the standard workspace-scoped component.
 */
export default function AdminAiSettingsPage() {
  const { workspaceId } = useParams<{ workspaceId?: string }>();
  const { t } = useTranslation();
  const [workspaces, setWorkspaces] = useState<Workspace[] | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setLoading(true);
    listWorkspaces()
      .then(setWorkspaces)
      .catch((err) => setError(describeError(err)))
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <LoadingState message={t('aiSettings.loading')} />;
  if (error) return <ErrorState message={error} />;
  if (!workspaces) return null;

  // A workspace is selected via the URL — render the standard page.
  if (workspaceId) {
    return <WorkspaceAiSettingsPage />;
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight flex items-center gap-2">
          <Sparkles className="h-5 w-5" /> {t('aiSettings.adminTitle')}
        </h1>
        <p className="text-sm text-muted-foreground">
          {t('aiSettings.adminSubtitle')}
        </p>
      </div>

      {workspaces.length === 0 ? (
        <ErrorState message={t('aiSettings.noWorkspaces')} />
      ) : (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {workspaces.map((w) => (
            <Card key={w.id} className="hover:border-primary/50 transition-colors">
              <CardContent className="pt-6 space-y-2">
                <div className="text-sm font-semibold">{w.name}</div>
                <div className="text-xs text-muted-foreground">
                  <code>{w.slug}</code>
                </div>
                <Button asChild size="sm" className="w-full">
                  <Link to={`/admin/ai-settings/${w.id}`}>
                    <Sparkles className="mr-1 h-3 w-3" /> {t('aiSettings.configure')}
                  </Link>
                </Button>
              </CardContent>
            </Card>
          ))}
        </div>
      )}

      <Button variant="ghost" size="sm" asChild>
        <Link to="/admin/users">
          <ArrowLeft className="mr-1 h-4 w-4" /> {t('common.back')}
        </Link>
      </Button>
    </div>
  );
}
