import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import apiClient from '../services/api';
import { ShieldAlert, FileCode, Wand2 } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from './ui/card';
import { Badge } from './ui/badge';
import { LoadingState } from './ui/states';

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
  const [gaps, setGaps] = useState<GapEntity[]>([]);
  const [loading, setLoading] = useState(true);

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
              <button className="shrink-0 flex items-center text-xs font-medium bg-orange-100 text-orange-700 hover:bg-orange-200 px-3 py-1.5 rounded dark:bg-orange-900/40 dark:text-orange-400 dark:hover:bg-orange-900/60 transition-colors">
                <Wand2 className="w-3 h-3 mr-1" /> {t('coverage.generateDoc', 'Tạo Doc bằng AI')}
              </button>
            </li>
          ))}
        </ul>
      </CardContent>
    </Card>
  );
}