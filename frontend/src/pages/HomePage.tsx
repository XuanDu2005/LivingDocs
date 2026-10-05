import { Link } from 'react-router-dom';
import {
  ArrowRight,
  BookOpen,
  GitBranch,
  Sparkles,
  Shield,
  Activity,
  Stethoscope,
  CheckCircle2,
} from 'lucide-react';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '../components/ui/card';
import { useAuth } from '../contexts/AuthContext';

const features = [
  {
    icon: Sparkles,
    title: 'AI-assisted drafts',
    body: 'Generate first-pass docs from pull requests, link them to code entities, and iterate with inline reviews.',
  },
  {
    icon: GitBranch,
    title: 'Lives with your code',
    body: 'Drift detection flags when reality moves away from the docs. CI can block merges until docs catch up.',
  },
  {
    icon: Shield,
    title: 'Review before publish',
    body: 'Reviewers approve each version. AI-authored drafts always go through a human-in-the-loop checkpoint.',
  },
  {
    icon: Stethoscope,
    title: 'Health at a glance',
    body: 'Freshness, coverage, drift and confidence scores roll up into one dashboard per workspace.',
  },
];

export default function HomePage() {
  const { isAuthenticated } = useAuth();

  return (
    <div className="space-y-16 py-8">
      <section className="grid gap-8 md:grid-cols-2 md:items-center">
        <div className="space-y-6">
          <Badge variant="muted" className="w-fit">
            <Activity className="mr-1 h-3 w-3" />
            v0.2 — Foundation
          </Badge>
          <h1 className="text-4xl font-semibold leading-tight tracking-tight md:text-5xl">
            Living documentation
            <span className="block bg-gradient-to-r from-primary to-info bg-clip-text text-transparent">
              that evolves with your code
            </span>
          </h1>
          <p className="max-w-prose text-lg text-muted-foreground">
            LivingDocs keeps the docs next to the pull request. Generate drafts from
            code, route them through human review, surface drift before it ships.
          </p>
          <div className="flex flex-wrap gap-3">
            {isAuthenticated ? (
              <Button asChild size="lg">
                <Link to="/dashboard">
                  Go to dashboard <ArrowRight className="ml-1 h-4 w-4" />
                </Link>
              </Button>
            ) : (
              <>
                <Button asChild size="lg">
                  <Link to="/register">
                    Get started <ArrowRight className="ml-1 h-4 w-4" />
                  </Link>
                </Button>
                <Button asChild size="lg" variant="outline">
                  <Link to="/login">Log in</Link>
                </Button>
              </>
            )}
          </div>
          <div className="flex flex-wrap items-center gap-x-6 gap-y-2 text-sm text-muted-foreground">
            <span className="flex items-center gap-1.5">
              <CheckCircle2 className="h-4 w-4 text-success" /> GitHub-native
            </span>
            <span className="flex items-center gap-1.5">
              <CheckCircle2 className="h-4 w-4 text-success" /> Markdown + RST
            </span>
            <span className="flex items-center gap-1.5">
              <CheckCircle2 className="h-4 w-4 text-success" /> Self-hostable
            </span>
          </div>
        </div>
        <div className="relative">
          <div className="absolute inset-0 -z-10 rounded-3xl bg-gradient-to-br from-primary/20 via-info/10 to-transparent blur-3xl" />
          <Card className="border-primary/20 bg-card/80 shadow-2xl backdrop-blur">
            <CardHeader>
              <div className="flex items-center gap-2 text-xs text-muted-foreground">
                <span className="h-2 w-2 rounded-full bg-success" />
                pr-241 · feat(checkout): wallet support
              </div>
              <CardTitle>Generated first draft</CardTitle>
              <CardDescription>Linked to 4 code entities · confidence 0.86</CardDescription>
            </CardHeader>
            <CardContent className="space-y-3 text-sm">
              <div className="rounded-md border bg-muted/40 p-3 font-mono text-xs leading-relaxed">
                <span className="text-info">##</span> Wallet checkout
                <br />
                <br />
                Adds a <code className="rounded bg-muted px-1">WalletService</code> that
                bridges <code className="rounded bg-muted px-1">PaymentIntent</code> to
                a Stripe wallet source.
                <br />
                <br />
                <span className="text-info">##</span> Trade-offs
                <br />
                - Idempotency keys cached for 24h
                <br />
                - Refunds settle async
              </div>
              <div className="flex items-center justify-between text-xs text-muted-foreground">
                <span>→ In review by @alice</span>
                <Badge variant="warning">Drift: 2 stale sections</Badge>
              </div>
            </CardContent>
          </Card>
        </div>
      </section>

      <section>
        <div className="mb-8 text-center">
          <h2 className="text-2xl font-semibold tracking-tight">What you get</h2>
          <p className="mt-2 text-sm text-muted-foreground">
            The four jobs LivingDocs is built for, in one tool.
          </p>
        </div>
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
          {features.map((f) => (
            <Card key={f.title} className="bg-card/50">
              <CardHeader>
                <f.icon className="h-5 w-5 text-primary" />
                <CardTitle className="text-base">{f.title}</CardTitle>
              </CardHeader>
              <CardContent>
                <p className="text-sm text-muted-foreground">{f.body}</p>
              </CardContent>
            </Card>
          ))}
        </div>
      </section>

      <section className="rounded-2xl border bg-card/30 p-8 text-center">
        <BookOpen className="mx-auto mb-3 h-8 w-8 text-primary" />
        <h2 className="text-xl font-semibold">Ready to give your docs a pulse?</h2>
        <p className="mx-auto mt-1 max-w-md text-sm text-muted-foreground">
          Spin up a workspace, connect a GitHub repo, and let the AI service draft your
          first document in under two minutes.
        </p>
        <div className="mt-4 flex justify-center gap-2">
          <Button asChild>
            <Link to={isAuthenticated ? '/workspaces' : '/register'}>
              {isAuthenticated ? 'Open workspaces' : 'Create your account'}
            </Link>
          </Button>
          <Button asChild variant="ghost">
            <Link to="/about">How it works</Link>
          </Button>
        </div>
      </section>
    </div>
  );
}
