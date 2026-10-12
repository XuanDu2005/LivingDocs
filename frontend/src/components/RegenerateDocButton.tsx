import { useState } from 'react';
import apiClient from '../services/api';
import { RefreshCw, CheckCircle2 } from 'lucide-react';
import { Button } from './ui/button';
import { useTranslation } from 'react-i18next';

interface RegenerateDocButtonProps {
  workspaceId: string;
  documentId: string;
}

export default function RegenerateDocButton({ workspaceId, documentId }: RegenerateDocButtonProps) {
  // KHAI BÁO ĐÚNG CHUẨN: Nằm ngay đầu tiên bên trong component chính
  const { t } = useTranslation();
  
  const [loading, setLoading] = useState(false);
  const [success, setSuccess] = useState(false);

  const handleRegenerate = async () => {
    setLoading(true);
    try {
      await apiClient.post(`/workspaces/${workspaceId}/documents/${documentId}/regenerate`);
      setSuccess(true);
      setTimeout(() => {
        setSuccess(false);
        window.location.reload(); 
      }, 2000);
    } catch (error) {
      console.error("Lỗi khi tái tạo tài liệu:", error);
      alert("Có lỗi xảy ra khi gọi AI Service.");
    } finally {
      setLoading(false);
    }
  };

  return (
    <Button
      variant="outline"
      onClick={handleRegenerate}
      disabled={loading || success}
      className="border-blue-200 text-blue-700 hover:bg-blue-50 dark:border-blue-900 dark:text-blue-400 dark:hover:bg-blue-900/30 shadow-sm"
    >
      {loading ? (
        <><RefreshCw className="w-4 h-4 mr-2 animate-spin" /> {t('documentDetail.regenerateDocLoading', 'Đang chờ AI viết lại...')}</>
      ) : success ? (
        <><CheckCircle2 className="w-4 h-4 mr-2 text-green-500" /> {t('documentDetail.regenerateDocSuccess', 'Đã tạo bản nháp mới!')}</>
      ) : (
        <><RefreshCw className="w-4 h-4 mr-2" /> {t('documentDetail.regenerateDoc', 'AI Cập nhật lại tài liệu')}</>
      )}
    </Button>
  );
}