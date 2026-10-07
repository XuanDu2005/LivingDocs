import { FormEvent, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Check, LogOut, User } from 'lucide-react';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Badge } from '../components/ui/badge';
import {
  Card, CardContent, CardDescription, CardHeader, CardTitle,
} from '../components/ui/card';
import { LoadingState, ErrorState } from '../components/ui/states';
import { LinkedAccountsCard } from '../components/auth/LinkedAccountsCard';
import { useAuth } from '../contexts/AuthContext';
import { updateCurrentUser } from '../services/users';
import { describeError } from '../services/auth';

export default function ProfilePage() {
  const { user, refreshUser, logout } = useAuth();
  const navigate = useNavigate();

  const [displayName, setDisplayName] = useState<string>('');
  const [email, setEmail] = useState<string>('');
  const [submitting, setSubmitting] = useState<boolean>(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (user) {
      setDisplayName(user.displayName);
      setEmail(user.email);
    }
  }, [user]);

  if (!user) return <LoadingState message="Loading profile…" />;

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setSubmitting(true);
    setMessage(null);
    setError(null);
    try {
      await updateCurrentUser({ displayName, email });
      await refreshUser();
      setMessage('Profile updated successfully.');
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSubmitting(false);
    }
  }

  function handleSignOut() {
    logout();
    navigate('/login', { replace: true });
  }

  return (
    <div className="mx-auto max-w-xl space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Your profile</h1>
        <p className="text-sm text-muted-foreground mt-1">
          Update how you appear to teammates across the platform.
        </p>
      </div>

      {/* Profile form */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base flex items-center gap-2">
            <User className="h-4 w-4" /> Profile information
          </CardTitle>
          <CardDescription>Your display name and email address.</CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={onSubmit} className="space-y-4">
            <div className="space-y-1.5">
              <Label htmlFor="profile-display">Display name</Label>
              <Input
                id="profile-display"
                type="text"
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
                required
                maxLength={120}
              />
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="profile-email">Email</Label>
              <Input
                id="profile-email"
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                disabled
                className="opacity-60 cursor-not-allowed"
              />
              <p className="text-xs text-muted-foreground">Email cannot be changed here.</p>
            </div>

            {/* User info */}
            <div className="rounded-md border bg-muted/50 p-3 space-y-1.5">
              <div className="flex items-center justify-between text-sm">
                <span className="text-muted-foreground">User ID</span>
                <code className="text-xs font-mono">{user.id}</code>
              </div>
              <div className="flex items-center justify-between text-sm">
                <span className="text-muted-foreground">Created</span>
                <span className="text-xs">
                  {new Date(user.createdAt).toLocaleDateString()}
                </span>
              </div>
              {user.role && (
                <div className="flex items-center justify-between text-sm">
                  <span className="text-muted-foreground">Role</span>
                  <Badge variant="info">{user.role}</Badge>
                </div>
              )}
            </div>

            {message && (
              <div className="flex items-center gap-2 text-sm text-green-600">
                <Check className="h-4 w-4" /> {message}
              </div>
            )}
            {error && <ErrorState message={error} />}

            <Button type="submit" disabled={submitting}>
              {submitting ? 'Saving…' : 'Save changes'}
            </Button>
          </form>
        </CardContent>
      </Card>

      {/* Linked OAuth accounts */}
      <LinkedAccountsCard />

      {/* Sign out */}
      <Card>
        <CardContent className="pt-6">
          <div className="flex items-center justify-between">
            <div>
              <div className="text-sm font-medium">Sign out</div>
              <div className="text-xs text-muted-foreground">
                Sign out of your LivingDocs account on this device.
              </div>
            </div>
            <Button variant="destructive" onClick={handleSignOut}>
              <LogOut className="mr-1 h-4 w-4" /> Sign out
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
