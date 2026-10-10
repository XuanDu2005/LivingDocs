import { useState } from 'react';
import apiClient from '../services/api';
import { RefreshCw, CheckCircle2 } from 'lucide-react';
import { Button } from './ui/button';

interface RegenerateDocButtonProps {
  workspaceId: string;
  documentId: string;
}

export default function RegenerateDocButton({ workspaceId, documentId }: RegenerateDocButtonProps) {
  const [loading, setLoading] = useState(false);
  const [success, setSuccess] = useState(false);

  const handleRegenerate = async () => {
    setLoading(true);
    try {
      await apiClient.post(`/workspaces/${workspaceId}/documents/${documentId}/regenerate`);
      setSuccess(true);
      setTimeout(() => {
        setSuccess(false);
        // Tải lại trang để bảng Lịch sử phiên bản (Version History) hiện bản nháp mới
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
        <><RefreshCw className="w-4 h-4 mr-2 animate-spin" /> Đang nhờ AI viết lại...</>
      ) : success ? (
        <><CheckCircle2 className="w-4 h-4 mr-2 text-green-500" /> Đã tạo bản nháp mới!</>
      ) : (
        <><RefreshCw className="w-4 h-4 mr-2" /> AI Cập nhật lại tài liệu</>
      )}
    </Button>
  );
}