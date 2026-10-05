import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Github, Loader2 } from 'lucide-react';
import { Button } from '../components/ui/button';
import { githubApi } from '../services/github';
import { describeError } from '../services/auth';
import { ApiError } from '../services/auth';

export default function GithubCallbackPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const code = params.get('code');
    const state = params.get('state');
    const expected = sessionStorage.getItem('github-oauth-state');

    if (!code || !state || !expected) {
      setError('Missing OAuth parameters in the callback URL.');
      return;
    }

    sessionStorage.removeItem('github-oauth-state');

    githubApi
      .completeCallback(code, state, expected)
      .then(() => navigate('/workspaces', { replace: true }))
      .catch((err: unknown) => {
        setError(err instanceof ApiError ? err.message : describeError(err));
      });
  }, [params, navigate]);

  if (error) {
    return (
      <div className="flex min-h-[60vh] flex-col items-center justify-center gap-4 p-6">
        <div className="rounded-full bg-destructive/10 p-4">
          <Github className="h-10 w-10 text-destructive" />
        </div>
        <div className="text-center space-y-2">
          <h1 className="text-xl font-bold">GitHub connection failed</h1>
          <p className="text-sm text-muted-foreground max-w-sm">{error}</p>
        </div>
        <Button onClick={() => navigate('/workspaces')} variant="outline">
          Back to workspaces
        </Button>
      </div>
    );
  }

  return (
    <div className="flex min-h-[60vh] flex-col items-center justify-center gap-4 p-6">
      <div className="rounded-full bg-primary/10 p-4">
        <Github className="h-10 w-10 text-primary" />
      </div>
      <div className="text-center space-y-2">
        <h1 className="text-xl font-bold">Connecting GitHub…</h1>
        <p className="text-sm text-muted-foreground flex items-center justify-center gap-2">
          <Loader2 className="h-4 w-4 animate-spin" />
          Finishing the OAuth handshake.
        </p>
      </div>
    </div>
  );
}
