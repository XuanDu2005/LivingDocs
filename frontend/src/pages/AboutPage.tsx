import { useTranslation } from 'react-i18next';
import { Bot, Check, GitBranch, Server, X } from 'lucide-react';
import { Badge } from '../components/ui/badge';
import {
  Card, CardContent, CardHeader, CardTitle,
} from '../components/ui/card';

function RoadmapItem({ item, t }: { item: { title: string; done: boolean; description: string }; t: (k: string) => string }) {
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
          {item.done
            ? <Badge variant="success" className="text-xs">{t('about.roadmapDone')}</Badge>
            : <Badge variant="muted" className="text-xs">{t('about.roadmapInProgress')}</Badge>}
        </div>
        <p className="text-xs text-muted-foreground">{item.description}</p>
      </div>
    </div>
  );
}

export default function AboutPage() {
  const { t } = useTranslation();

  const ROADMAP = [
    { titleKey: 'about.rm1Title', descriptionKey: 'about.rm1Desc', done: true },
    { titleKey: 'about.rm2Title', descriptionKey: 'about.rm2Desc', done: false },
    { titleKey: 'about.rm3Title', descriptionKey: 'about.rm3Desc', done: false },
    { titleKey: 'about.rm4Title', descriptionKey: 'about.rm4Desc', done: false },
  ];

  const githubFeatures = ['github.repositorySync', 'github.prWebhooks', 'github.branchDiffs'];
  const backendFeatures = ['github.docCrud', 'github.driftEngine', 'github.reviewWorkflow', 'github.templateMgmt', 'github.oauth'];
  const aiFeatures = ['github.codeToMd', 'github.semanticDrift', 'github.entityExtraction', 'github.vectorSearch'];

  return (
    <div className="space-y-8">
      <div className="text-center space-y-3 py-8">
        <h1 className="text-4xl font-bold tracking-tight">{t('common.appName')}</h1>
        <p className="text-lg text-muted-foreground max-w-2xl mx-auto">
          {t('about.hero')}
        </p>
        <div className="flex justify-center gap-2 pt-2">
          <Badge variant="info" className="text-sm px-3 py-1">{t('about.aiPowered')}</Badge>
          <Badge variant="success" className="text-sm px-3 py-1">{t('about.githubNative')}</Badge>
          <Badge variant="default" className="text-sm px-3 py-1">{t('about.openSource')}</Badge>
        </div>
      </div>

      <div>
        <h2 className="text-xl font-semibold mb-4 text-center">{t('about.architecture')}</h2>
        <div className="grid gap-4 md:grid-cols-3">
          <Card className="border-primary/30">
            <CardHeader>
              <CardTitle className="text-base flex items-center gap-2">
                <GitBranch className="h-4 w-4 text-primary" /> GitHub
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-2">
              <p className="text-sm text-muted-foreground">{t('about.githubDesc')}</p>
              <div className="space-y-1">
                {githubFeatures.map((f) => (
                  <div key={f} className="flex items-center gap-1.5 text-xs">
                    <div className="h-1.5 w-1.5 rounded-full bg-primary" />
                    <span>{t(f)}</span>
                  </div>
                ))}
              </div>
            </CardContent>
          </Card>

          <Card className="border-primary/30">
            <CardHeader>
              <CardTitle className="text-base flex items-center gap-2">
                <Server className="h-4 w-4 text-primary" /> {t('about.backendTitle')}
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-2">
              <p className="text-sm text-muted-foreground">{t('about.backendDesc')}</p>
              <div className="space-y-1">
                {backendFeatures.map((f) => (
                  <div key={f} className="flex items-center gap-1.5 text-xs">
                    <div className="h-1.5 w-1.5 rounded-full bg-primary" />
                    <span>{t(f)}</span>
                  </div>
                ))}
              </div>
            </CardContent>
          </Card>

          <Card className="border-primary/30">
            <CardHeader>
              <CardTitle className="text-base flex items-center gap-2">
                <Bot className="h-4 w-4 text-primary" /> {t('about.aiServiceTitle')}
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-2">
              <p className="text-sm text-muted-foreground">{t('about.aiServiceDesc')}</p>
              <div className="space-y-1">
                {aiFeatures.map((f) => (
                  <div key={f} className="flex items-center gap-1.5 text-xs">
                    <div className="h-1.5 w-1.5 rounded-full bg-primary" />
                    <span>{t(f)}</span>
                  </div>
                ))}
              </div>
            </CardContent>
          </Card>
        </div>

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

      <div>
        <h2 className="text-xl font-semibold mb-4 text-center">{t('about.roadmap')}</h2>
        <div className="max-w-2xl mx-auto space-y-3">
          {ROADMAP.map((item) => (
            <RoadmapItem
              key={item.titleKey}
              item={{ title: t(item.titleKey), done: item.done, description: t(item.descriptionKey) }}
              t={t}
            />
          ))}
        </div>
      </div>
    </div>
  );
}