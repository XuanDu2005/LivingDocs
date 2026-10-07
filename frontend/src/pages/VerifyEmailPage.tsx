import { FormEvent, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { MailCheck } from 'lucide-react';

import { Button } from '../components/ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '../components/ui/card';
import { OtpInput } from '../components/auth/OtpInput';
import { useAuth } from '../contexts/AuthContext';
import { describeError } from '../services/auth';
import { resendVerification } from '../services/authApi';

const RESEND_COOLDOWN_SECONDS = 30;

export default function VerifyEmailPage() {
  const { verifyEmail } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const email = params.get('email') ?? '';

  const [code, setCode] = useState<string>('');
  const [submitting, setSubmitting] = useState<boolean>(false);
  const [resending, setResending] = useState<boolean>(false);
  const [cooldown, setCooldown] = useState<number>(0);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);

  if (!email) {
    return (
      <div className="flex min-h-[70vh] items-center justify-center">
        <Card className="w-full max-w-md">
          <CardHeader>
            <CardTitle>Thiếu địa chỉ email</CardTitle>
            <CardDescription>
              Vui lòng quay lại trang đăng ký để bắt đầu lại.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <Button asChild className="w-full">
              <Link to="/register">Về trang đăng ký</Link>
            </Button>
          </CardContent>
        </Card>
      </div>
    );
  }

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(null);
    setInfo(null);
    if (code.length !== 6) {
      setError('Vui lòng nhập đủ 6 chữ số.');
      return;
    }
    setSubmitting(true);
    try {
      await verifyEmail({ email, code });
      const next = params.get('next') ?? '/dashboard';
      navigate(next, { replace: true });
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSubmitting(false);
    }
  }

  async function onResend() {
    if (cooldown > 0) return;
    setResending(true);
    setError(null);
    setInfo(null);
    try {
      await resendVerification(email);
      setInfo('Đã gửi lại mã mới. Vui lòng kiểm tra email.');
      setCooldown(RESEND_COOLDOWN_SECONDS);
      const id = window.setInterval(() => {
        setCooldown((c) => {
          if (c <= 1) {
            window.clearInterval(id);
            return 0;
          }
          return c - 1;
        });
      }, 1000);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setResending(false);
    }
  }

  return (
    <div className="flex min-h-[70vh] items-center justify-center">
      <Card className="w-full max-w-md">
        <CardHeader>
          <div className="flex h-10 w-10 items-center justify-center rounded-md bg-primary/10 text-primary">
            <MailCheck className="h-5 w-5" />
          </div>
          <CardTitle>Xác thực email</CardTitle>
          <CardDescription>
            Chúng tôi đã gửi mã 6 chữ số đến <b>{email}</b>. Mã có hiệu lực trong 15 phút.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={onSubmit} className="space-y-6">
            <OtpInput
              value={code}
              onChange={setCode}
              length={6}
              disabled={submitting}
              onComplete={(v) => setCode(v)}
            />
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
            <Button type="submit" disabled={submitting || code.length !== 6} className="w-full">
              {submitting ? 'Đang xác thực…' : 'Xác thực'}
            </Button>
            <div className="flex items-center justify-between text-sm">
              <Link to="/login" className="text-muted-foreground hover:underline">
                ← Về trang đăng nhập
              </Link>
              <Button
                type="button"
                variant="link"
                size="sm"
                onClick={onResend}
                disabled={resending || cooldown > 0}
              >
                {cooldown > 0 ? `Gửi lại sau ${cooldown}s` : 'Gửi lại mã'}
              </Button>
            </div>
          </form>
        </CardContent>
      </Card>
    </div>
  );
}
