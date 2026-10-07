import { useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Loader2, ShieldAlert } from 'lucide-react';

import { Button } from '../components/ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '../components/ui/card';
import { authStorage } from '../services/auth';
import { fetchCurrentUser } from '../services/users';

type Status = 'processing' | 'success' | 'error';

/**
 * Target of the OAuth 2.0 redirect. The backend 302s the browser here
 * with {@code ?token=...&provider=...&next=...} (or {@code ?error=...}).
 *
 * <p>We exchange the one-shot token for a {@code fetchCurrentUser}
 * round-trip so the local cache (displayName, role, etc.) is fully
 * populated, then navigate to the original destination.
 */
export default function OAuthCallbackPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const [status, setStatus] = useState<Status>('processing');
  const [errorCode, setErrorCode] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const ran = useRef<boolean>(false);

  useEffect(() => {
    if (ran.current) return;
    ran.current = true;
    const token = params.get('token');
    const error = params.get('error');
    const next = params.get('next') ?? '/dashboard';
    const provider = params.get('provider');

    if (error) {
      setStatus('error');
      setErrorCode(error);
      setErrorMessage(params.get('message'));
      return;
    }
    if (!token) {
      setStatus('error');
      setErrorCode('missing_token');
      setErrorMessage(
        'The OAuth provider did not return an access token. Please try again.',
      );
      return;
    }
    authStorage.setToken(token);
    fetchCurrentUser()
      .then((user) => {
        authStorage.setUser(user);
        setStatus('success');
        // Brief delay so the user sees the success card before navigation.
        window.setTimeout(() => navigate(next, { replace: true }), 600);
      })
      .catch((e) => {
        authStorage.clearToken();
        authStorage.clearUser();
        setStatus('error');
        setErrorCode('user_load_failed');
        setErrorMessage(e instanceof Error ? e.message : 'Failed to load profile');
      });
    void provider;
  }, [params, navigate]);

  if (status === 'processing') {
    return (
      <div className="flex min-h-[70vh] items-center justify-center">
        <Card className="w-full max-w-md">
          <CardHeader>
            <div className="flex h-10 w-10 items-center justify-center rounded-md bg-primary/10 text-primary">
              <Loader2 className="h-5 w-5 animate-spin" />
            </div>
            <CardTitle>Đang hoàn tất đăng nhập…</CardTitle>
            <CardDescription>
              Chúng tôi đang trao đổi mã với nhà cung cấp và tải hồ sơ của bạn.
            </CardDescription>
          </CardHeader>
        </Card>
      </div>
    );
  }
  if (status === 'error') {
    const friendly = friendlyErrorMessage(errorCode, errorMessage);
    return (
      <div className="flex min-h-[70vh] items-center justify-center">
        <Card className="w-full max-w-md">
          <CardHeader>
            <div className="flex h-10 w-10 items-center justify-center rounded-md bg-destructive/10 text-destructive">
              <ShieldAlert className="h-5 w-5" />
            </div>
            <CardTitle>Đăng nhập thất bại</CardTitle>
            <CardDescription>{friendly}</CardDescription>
          </CardHeader>
          <CardContent className="space-y-2">
            <Button className="w-full" onClick={() => navigate('/login', { replace: true })}>
              Về trang đăng nhập
            </Button>
            <Button
              variant="outline"
              className="w-full"
              onClick={() => navigate('/register', { replace: true })}
            >
              Tạo tài khoản mới
            </Button>
          </CardContent>
        </Card>
      </div>
    );
  }
  return (
    <div className="flex min-h-[70vh] items-center justify-center">
      <Card className="w-full max-w-md">
        <CardHeader>
          <CardTitle>Đăng nhập thành công</CardTitle>
          <CardDescription>Đang chuyển hướng bạn đến trang chính…</CardDescription>
        </CardHeader>
      </Card>
    </div>
  );
}

function friendlyErrorMessage(code: string | null, fallback: string | null): string {
  switch (code) {
    case 'invalid_state':
      return 'Phiên đăng nhập đã hết hạn. Vui lòng thử lại.';
    case 'merge_required':
      return 'Tài khoản OAuth này đã được liên kết với một tài khoản LivingDocs khác. Vui lòng đăng nhập với tài khoản đó hoặc liên hệ hỗ trợ.';
    case 'link_failed':
      return 'Không thể liên kết tài khoản. Vui lòng thử lại sau.';
    case 'exchange_failed':
      return 'Nhà cung cấp từ chối trao đổi mã. Vui lòng thử lại.';
    case 'missing_token':
      return 'Không nhận được mã truy cập. Vui lòng thử lại.';
    default:
      return fallback ?? 'Đã có lỗi xảy ra trong quá trình đăng nhập.';
  }
}
