import { useTranslation } from 'react-i18next';
import { Badge } from '../components/ui/badge';

interface Tag {
  id: string;
  name: string;
  colorHex: string;
}

interface DocumentTagsProps {
  tags: Tag[];
  onRemove?: (tagId: string) => void; // Nếu bạn muốn click X để xóa thẻ
}

export function DocumentTags({ tags, onRemove }: DocumentTagsProps) {
  const { t } = useTranslation();
  if (!tags || tags.length === 0) return null;

  return (
    <div className="flex flex-wrap gap-2 mb-4">
      {tags.map(tag => (
        <Badge 
          key={tag.id} 
          style={{ backgroundColor: tag.colorHex, color: '#fff', padding: '4px 8px' }}
          className="flex items-center gap-1 font-medium"
        >
          {tag.name}
          {onRemove && (
            <button 
              onClick={() => onRemove(tag.id)} 
              className="ml-1 hover:text-red-300 focus:outline-none"
              title={t('documentTags.removeTag')}
            >
              ×
            </button>
          )}
        </Badge>
      ))}
    </div>
  );
}