import { FormEvent, useState } from 'react';
import { Link } from 'react-router-dom';
import { KeyRound } from 'lucide-react';

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
import { forgotPassword } from '../services/authApi';
import { describeError } from '../services/auth';

/**
 * "Forgot password" page — collects the email, calls
 * {@code POST /api/v1/auth/forgot-password}, and tells the user to
 * check their inbox. Always shows the same success message regardless
 * of whether the account exists.
 */
export default function ForgotPasswordPage() {
  const [email, setEmail] = useState<string>('');
  const [submitting, setSubmitting] = useState<boolean>(false);
  const [done, setDone] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await forgotPassword(email);
      setDone(true);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSubmitting(false);
    }
  }

  if (done) {
    return (
      <div className="flex min-h-[70vh] items-center justify-center">
        <Card className="w-full max-w-md">
          <CardHeader>
            <div className="flex h-10 w-10 items-center justify-center rounded-md bg-primary/10 text-primary">
              <KeyRound className="h-5 w-5" />
            </div>
            <CardTitle>Kiểm tra email của bạn</CardTitle>
            <CardDescription>
              Nếu tài khoản <b>{email}</b> tồn tại, chúng tôi đã gửi mã đặt lại mật khẩu
              đến email đó. Mã có hiệu lực trong 30 phút.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <Button asChild className="w-full">
              <Link to={`/reset-password?email=${encodeURIComponent(email)}`}>
                Tôi đã có mã — đặt lại mật khẩu
              </Link>
            </Button>
            <p className="mt-4 text-center text-sm text-muted-foreground">
              <Link to="/login" className="hover:underline">
                ← Quay lại đăng nhập
              </Link>
            </p>
          </CardContent>
        </Card>
      </div>
    );
  }

  return (
    <div className="flex min-h-[70vh] items-center justify-center">
      <Card className="w-full max-w-md">
        <CardHeader>
          <div className="flex h-10 w-10 items-center justify-center rounded-md bg-primary/10 text-primary">
            <KeyRound className="h-5 w-5" />
          </div>
          <CardTitle>Quên mật khẩu</CardTitle>
          <CardDescription>
            Nhập email đã đăng ký. Chúng tôi sẽ gửi mã xác nhận để bạn đặt lại mật khẩu.
          </CardDescription>
        </CardHeader>
        <CardContent>
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
            {error && (
              <div className="rounded-md border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive">
                {error}
              </div>
            )}
            <Button type="submit" disabled={submitting} className="w-full">
              {submitting ? 'Đang gửi…' : 'Gửi mã đặt lại'}
            </Button>
          </form>
          <p className="mt-4 text-center text-sm text-muted-foreground">
            Nhớ mật khẩu rồi?{' '}
            <Link to="/login" className="text-primary hover:underline">
              Đăng nhập
            </Link>
            .
          </p>
        </CardContent>
      </Card>
    </div>
  );
}
