import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Compass, Home } from 'lucide-react';
import { Button } from '../components/ui/button';
import { EmptyState } from '../components/ui/states';

export default function NotFoundPage() {
  const { t } = useTranslation();
  return (
    <div className="flex min-h-[60vh] items-center justify-center">
      <EmptyState
        icon={<Compass className="h-12 w-12" />}
        title={t('notFound.title')}
        description={t('notFound.description')}
        action={
          <Button asChild>
            <Link to="/">
              <Home className="mr-1 h-4 w-4" /> {t('notFound.backHome')}
            </Link>
          </Button>
        }
      />
    </div>
  );
}