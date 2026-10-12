import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import apiClient from '../services/api';
import { ShieldAlert, FileCode, Wand2, Loader2 } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from './ui/card';
import { Badge } from './ui/badge';
import { Button } from './ui/button';
import { LoadingState } from './ui/states';
import { describeError } from '../services/auth';

interface CoverageGapsPanelProps {
  workspaceId: string;
  repositoryId: string;
}

interface GapEntity {
  id: string;
  name: string;
  kind: string;
  file_path: string;
  signature: string;
}

export default function CoverageGapsPanel({ workspaceId, repositoryId }: CoverageGapsPanelProps) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [gaps, setGaps] = useState<GapEntity[]>([]);
  const [loading, setLoading] = useState(true);
  const [generatingId, setGeneratingId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const fetchGaps = async () => {
      try {
        const { data } = await apiClient.get<GapEntity[]>(
          `/workspaces/${workspaceId}/repositories/${repositoryId}/code-entities/gaps`
        );
        setGaps(data);
      } catch (error) {
        console.error('Lỗi khi tải danh sách Coverage Gaps:', error);
      } finally {
        setLoading(false);
      }
    };
    if (workspaceId && repositoryId) {
      fetchGaps();
    }
  }, [workspaceId, repositoryId]);

  // Build a stable slug from a code entity (e.g. "ClassName::methodName" -> "classname-methodname")
  function buildSlug(entity: GapEntity): string {
    const base = entity.name
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/(^-|-$)/g, '');
    return base.slice(0, 80) || 'entity';
  }

  // Build a documentation body prompt based on the code entity
  function buildInitialBody(entity: GapEntity): string {
    return [
      `# ${entity.name}`,
      '',
      `> Auto-generated stub for **${entity.kind}** at \`${entity.file_path}\`.`,
      '',
      entity.signature ? `## Signature\n\n\`\`\`\n${entity.signature}\n\`\`\`\n` : '',
      '## Overview',
      '',
      '_Pending AI generation — click "Generate with AI" to fill in._',
    ].join('\n');
  }

  async function handleGenerate(gap: GapEntity) {
    if (!workspaceId || !repositoryId) return;
    setGeneratingId(gap.id);
    setError(null);
    try {
      // 1. Create a new document pre-filled with the entity info
      const title = gap.name;
      const slug = buildSlug(gap) + '-' + Date.now().toString(36);
      const initialBody = buildInitialBody(gap);
      const docType = 'AUTO_' + (gap.kind || 'CODE').toUpperCase();

      const created = await apiClient
        .post<{ id: string }>(`/workspaces/${workspaceId}/documents`, {
          title,
          slug,
          docType,
          summary: `Auto-created from coverage gap at ${gap.file_path}`,
          repositoryId,
          autoUpdateEnabled: true,
          initialBody,
        })
        .then((r) => r.data);

      // 2. Trigger AI generation for the new document
      await apiClient.post(
        `/workspaces/${workspaceId}/documents/${created.id}/generate`,
        { sourceFiles: [] },
      );

      // 3. Link the code entity to the new document so the gap is filled
      try {
        await apiClient.post(
          `/workspaces/${workspaceId}/documents/${created.id}/links`,
          { codeEntityId: gap.id, linkKind: 'AUTO', confidence: 1.0 },
        );
      } catch {
        // Non-fatal: doc was created even if link failed
      }

      // 4. Navigate to the new document so the user can review the AI draft
      navigate(`/workspaces/${workspaceId}/documents/${created.id}`);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setGeneratingId(null);
    }
  }

  if (loading) {
    return (
      <div className="mt-4">
        <LoadingState message={t('coverage.scanning', 'Đang quét khoảng trống tài liệu...')} />
      </div>
    );
  }

  if (gaps.length === 0) {
    return (
      <Card className="mt-4 border-green-200 dark:border-green-900 bg-green-50/30 dark:bg-green-900/10">
        <CardContent className="p-6 flex flex-col items-center justify-center text-green-600 dark:text-green-400">
          <ShieldAlert className="w-8 h-8 mb-2" />
          <p className="font-medium">
            {t('coverage.allCovered', 'Tuyệt vời! 100% source code của Repository này đã được viết tài liệu.')}
          </p>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card className="mt-4 border-orange-200 dark:border-orange-900 overflow-hidden">
      <CardHeader className="bg-orange-50 dark:bg-orange-900/20 pb-4">
        <CardTitle className="text-orange-700 dark:text-orange-400 flex items-center text-lg">
          <ShieldAlert className="w-5 h-5 mr-2" />
          {t('coverage.title', 'Coverage Gaps (Khoảng trống tài liệu)')}
          <Badge variant="outline" className="ml-3 bg-white dark:bg-black text-orange-600 border-orange-200">
            {t('coverage.detected', { count: gaps.length, defaultValue: `Phát hiện ${gaps.length} hàm/class` })}
          </Badge>
        </CardTitle>
        <p className="text-sm text-orange-600/80 dark:text-orange-400/80 mt-1 font-normal">
          {t('coverage.description', 'Các thực thể code dưới đây đã được phát hiện trong source code nhưng chưa được liên kết với bất kỳ tài liệu nào.')}
        </p>
      </CardHeader>
      <CardContent className="p-0">
        {error && (
          <div className="m-3 rounded-md border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive">
            {error}
          </div>
        )}
        <ul className="divide-y divide-gray-100 dark:divide-gray-800 max-h-[350px] overflow-y-auto">
          {gaps.map((gap) => (
            <li key={gap.id} className="p-4 hover:bg-gray-50 dark:hover:bg-gray-900/50 transition-colors flex items-start gap-3">
              <FileCode className="w-5 h-5 text-gray-400 mt-0.5 shrink-0" />
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2">
                  <span className="font-mono font-semibold text-blue-600 dark:text-blue-400 truncate">{gap.name}</span>
                  <span className="text-[10px] uppercase font-bold px-2 py-0.5 rounded bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-300">
                    {gap.kind}
                  </span>
                </div>
                <p className="text-xs text-gray-500 mt-1 font-mono truncate">{gap.file_path}</p>
                {gap.signature && (
                  <div className="mt-2 p-2 bg-gray-100/80 dark:bg-gray-800/80 rounded text-xs font-mono text-gray-600 dark:text-gray-300 overflow-x-auto whitespace-pre">
                    {gap.signature}
                  </div>
                )}
              </div>
              <Button
                size="sm"
                variant="outline"
                className="shrink-0 text-orange-700 border-orange-200 hover:bg-orange-100 dark:text-orange-400 dark:border-orange-800 dark:hover:bg-orange-900/40"
                onClick={() => void handleGenerate(gap)}
                disabled={generatingId !== null}
              >
                {generatingId === gap.id ? (
                  <>
                    <Loader2 className="w-3 h-3 mr-1 animate-spin" />
                    {t('coverage.generating', 'Đang tạo...')}
                  </>
                ) : (
                  <>
                    <Wand2 className="w-3 h-3 mr-1" />
                    {t('coverage.generateDoc', 'Tạo Doc bằng AI')}
                  </>
                )}
              </Button>
            </li>
          ))}
        </ul>
      </CardContent>
    </Card>
  );
}
