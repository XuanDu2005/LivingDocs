import { Bot, Check, GitBranch, Server, X } from 'lucide-react';
import { Badge } from '../components/ui/badge';
import {
  Card, CardContent, CardHeader, CardTitle,
} from '../components/ui/card';

const ROADMAP_ITEMS = [
  {
    title: 'GitHub integration — PR workflows, automated drift detection',
    done: true,
    description: 'Connect repositories, link documents, and trigger AI reviews on pull requests.',
  },
  {
    title: 'AI-assisted documentation generation and update',
    done: false,
    description: 'Generate and update documentation from source code using AI models.',
  },
  {
    title: 'Role-based access control — Member, Staff, Manager, Admin',
    done: false,
    description: 'Fine-grained permissions for who can view, edit, review, and publish documents.',
  },
  {
    title: 'Notifications, activity feed, and Slack/email digests',
    done: false,
    description: 'Keep your team informed about drift alerts, review requests, and new versions.',
  },
];

function RoadmapItem({ item }: { item: typeof ROADMAP_ITEMS[number] }) {
  return (
    <div className="flex items-start gap-3 rounded-md border p-4">
      <div className="mt-0.5 flex-shrink-0">
        {item.done ? (
          <div className="rounded-full bg-green-100 dark:bg-green-900/30 p-1">
            <Check className="h-4 w-4 text-green-600 dark:text-green-400" />
          </div>
        ) : (
          <div className="rounded-full bg-muted p-1">
            <X className="h-4 w-4 text-muted-foreground" />
          </div>
        )}
      </div>
      <div className="space-y-1">
        <div className="flex items-center gap-2">
          <span className="font-medium text-sm">{item.title}</span>
          {item.done ? (
            <Badge variant="success" className="text-xs">Done</Badge>
          ) : (
            <Badge variant="muted" className="text-xs">In progress</Badge>
          )}
        </div>
        <p className="text-xs text-muted-foreground">{item.description}</p>
      </div>
    </div>
  );
}

export default function AboutPage() {
  return (
    <div className="space-y-8">
      {/* Hero */}
      <div className="text-center space-y-3 py-8">
        <h1 className="text-4xl font-bold tracking-tight">
          LivingDocs
        </h1>
        <p className="text-lg text-muted-foreground max-w-2xl mx-auto">
          LivingDocs keeps your documentation next to your code — automatically generated,
          AI-assisted, and always in sync with your repository.
        </p>
        <div className="flex justify-center gap-2 pt-2">
          <Badge variant="info" className="text-sm px-3 py-1">AI-powered</Badge>
          <Badge variant="success" className="text-sm px-3 py-1">GitHub-native</Badge>
          <Badge variant="default" className="text-sm px-3 py-1">Open source</Badge>
        </div>
      </div>

      {/* Architecture diagram */}
      <div>
        <h2 className="text-xl font-semibold mb-4 text-center">Architecture</h2>
        <div className="grid gap-4 md:grid-cols-3">
          <Card className="border-primary/30">
            <CardHeader>
              <CardTitle className="text-base flex items-center gap-2">
                <GitBranch className="h-4 w-4 text-primary" /> GitHub
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-2">
              <p className="text-sm text-muted-foreground">
                Your source code lives here. LivingDocs listens to pushes and pull
                requests to detect drift and trigger documentation updates.
              </p>
              <div className="space-y-1">
                {['Repository sync', 'PR webhooks', 'Branch diffs'].map((f) => (
                  <div key={f} className="flex items-center gap-1.5 text-xs">
                    <div className="h-1.5 w-1.5 rounded-full bg-primary" />
                    <span>{f}</span>
                  </div>
                ))}
              </div>
            </CardContent>
          </Card>

          <Card className="border-primary/30">
            <CardHeader>
              <CardTitle className="text-base flex items-center gap-2">
                <Server className="h-4 w-4 text-primary" /> LivingDocs Backend
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-2">
              <p className="text-sm text-muted-foreground">
                Spring Boot API manages documents, versions, templates, drift alerts,
                and workspace membership. Persists everything to PostgreSQL.
              </p>
              <div className="space-y-1">
                {['Document CRUD + versioning', 'Drift detection engine', 'Review & publish workflow', 'Template management', 'GitHub OAuth'].map((f) => (
                  <div key={f} className="flex items-center gap-1.5 text-xs">
                    <div className="h-1.5 w-1.5 rounded-full bg-primary" />
                    <span>{f}</span>
                  </div>
                ))}
              </div>
            </CardContent>
          </Card>

          <Card className="border-primary/30">
            <CardHeader>
              <CardTitle className="text-base flex items-center gap-2">
                <Bot className="h-4 w-4 text-primary" /> AI Service
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-2">
              <p className="text-sm text-muted-foreground">
                An independent Python service that generates markdown from code,
                detects semantic drift, and links documents to source entities.
              </p>
              <div className="space-y-1">
                {['Code → Markdown generation', 'Semantic drift analysis', 'Entity extraction', 'Vector similarity search'].map((f) => (
                  <div key={f} className="flex items-center gap-1.5 text-xs">
                    <div className="h-1.5 w-1.5 rounded-full bg-primary" />
                    <span>{f}</span>
                  </div>
                ))}
              </div>
            </CardContent>
          </Card>
        </div>

        {/* Arrows between cards */}
        <div className="flex justify-center py-2">
          <svg width="400" height="40" className="hidden md:block" aria-hidden="true">
            <defs>
              <marker id="arrow" markerWidth="10" markerHeight="10" refX="9" refY="3" orient="auto">
                <path d="M0,0 L0,6 L9,3 z" fill="var(--primary)" />
              </marker>
            </defs>
            <line x1="100" y1="20" x2="300" y2="20" stroke="var(--primary)" strokeWidth="2" markerEnd="url(#arrow)" strokeDasharray="4,2" />
          </svg>
        </div>
      </div>

      {/* Roadmap */}
      <div>
        <h2 className="text-xl font-semibold mb-4 text-center">Roadmap</h2>
        <div className="max-w-2xl mx-auto space-y-3">
          {ROADMAP_ITEMS.map((item) => (
            <RoadmapItem key={item.title} item={item} />
          ))}
        </div>
      </div>
    </div>
  );
}
