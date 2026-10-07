import { FormEvent, useEffect, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { LogIn } from 'lucide-react';

import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '../components/ui/card';
import { SocialAuthButtons } from '../components/auth/SocialAuthButtons';
import { useAuth } from '../contexts/AuthContext';
import { describeError } from '../services/auth';

export default function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();

  const [email, setEmail] = useState<string>('');
  const [password, setPassword] = useState<string>('');
  const [submitting, setSubmitting] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);

  useEffect(() => {
    if (params.get('reset') === 'success') {
      setInfo('Mật khẩu đã được đặt lại. Vui lòng đăng nhập với mật khẩu mới.');
    }
  }, [params]);

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    setInfo(null);
    try {
      await login({ email, password });
      const next = params.get('next') ?? '/dashboard';
      navigate(next, { replace: true });
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="flex min-h-[70vh] items-center justify-center">
      <Card className="w-full max-w-md">
        <CardHeader>
          <div className="flex h-10 w-10 items-center justify-center rounded-md bg-primary/10 text-primary">
            <LogIn className="h-5 w-5" />
          </div>
          <CardTitle>Chào mừng trở lại</CardTitle>
          <CardDescription>
            Đăng nhập bằng tài khoản LivingDocs hoặc tiếp tục với Google/GitHub.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <SocialAuthButtons mode="login" />
          <div className="my-4 flex items-center gap-3 text-xs uppercase text-muted-foreground">
            <span className="h-px flex-1 bg-border" />
            <span>hoặc</span>
            <span className="h-px flex-1 bg-border" />
          </div>
          <form onSubmit={onSubmit} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="email">Email</Label>
              <Input
                id="email"
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                required
                autoComplete="email"
                placeholder="you@example.com"
              />
            </div>
            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <Label htmlFor="password">Mật khẩu</Label>
                <Link
                  to="/forgot-password"
                  className="text-xs text-muted-foreground hover:underline"
                >
                  Quên mật khẩu?
                </Link>
              </div>
              <Input
                id="password"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
                minLength={8}
                autoComplete="current-password"
              />
            </div>
            {error && (
              <div className="rounded-md border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive">
                {error}
              </div>
            )}
            {info && (
              <div className="rounded-md border border-emerald-500/40 bg-emerald-500/10 p-3 text-sm text-emerald-700 dark:text-emerald-300">
                {info}
              </div>
            )}
            <Button type="submit" disabled={submitting} className="w-full">
              {submitting ? 'Đang đăng nhập…' : 'Đăng nhập'}
            </Button>
          </form>
          <p className="mt-4 text-center text-sm text-muted-foreground">
            Chưa có tài khoản?{' '}
            <Link to="/register" className="text-primary hover:underline">
              Tạo tài khoản
            </Link>
            .
          </p>
        </CardContent>
      </Card>
    </div>
  );
}
