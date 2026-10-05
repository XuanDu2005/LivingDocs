import { Link } from 'react-router-dom';
import { Compass, Home } from 'lucide-react';
import { Button } from '../components/ui/button';
import { EmptyState } from '../components/ui/states';

export default function NotFoundPage() {
  return (
    <div className="flex min-h-[60vh] items-center justify-center">
      <EmptyState
        icon={<Compass className="h-12 w-12" />}
        title="404 — Page not found"
        description="The route you followed isn't part of LivingDocs."
        action={
          <Button asChild>
            <Link to="/">
              <Home className="mr-1 h-4 w-4" /> Back to home
            </Link>
          </Button>
        }
      />
    </div>
  );
}
