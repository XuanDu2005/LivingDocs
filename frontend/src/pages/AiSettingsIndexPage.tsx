import { useEffect, useState } from 'react';
import { Navigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../contexts/AuthContext';
import { listWorkspaces, Workspace } from '../services/workspaces';
import { LoadingState, ErrorState } from '../components/ui/states';
import { describeError } from '../services/auth';

export default function AiSettingsIndexPage() {
  const { user, isAuthenticated } = useAuth();
  const { t } = useTranslation();
  const [workspaces, setWorkspaces] = useState<Workspace[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!isAuthenticated) return;
    listWorkspaces()
      .then(setWorkspaces)
      .catch((err) => setError(describeError(err)));
  }, [isAuthenticated, user]);

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }
  if (error) return <ErrorState message={error} />;
  if (workspaces === null) return <LoadingState message={t('workspaces.loading')} />;
  if (workspaces.length === 0) {
    return (
      <ErrorState
        message={t('aiSettings.noMemberships')}
      />
    );
  }
  return <Navigate to={`/workspaces/${workspaces[0].id}/ai-settings`} replace />;
}
