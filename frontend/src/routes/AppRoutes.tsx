import { Route, Routes as RRoutes } from 'react-router-dom';

import { GuestRoute, ProtectedRoute } from '../components/ProtectedRoute';
import { AppLayout } from '../layouts/AppLayout';
import AboutPage from '../pages/AboutPage';
import AdminUsersPage from '../pages/AdminUsersPage';
import DashboardPage from '../pages/DashboardPage';
import DocumentDetailPage from '../pages/DocumentDetailPage';
import DocumentsListPage from '../pages/DocumentsListPage';
import DriftAlertsPage from '../pages/DriftAlertsPage';
import ForgotPasswordPage from '../pages/ForgotPasswordPage';
import GithubCallbackPage from '../pages/GithubCallbackPage';
import HealthDashboardPage from '../pages/HealthDashboardPage';
import HomePage from '../pages/HomePage';
import KnowledgeBasePage from '../pages/KnowledgeBasePage';
import LoginPage from '../pages/LoginPage';
import NewDocumentPage from '../pages/NewDocumentPage';
import NotFoundPage from '../pages/NotFoundPage';
import OAuthCallbackPage from '../pages/OAuthCallbackPage';
import ProfilePage from '../pages/ProfilePage';
import RegisterPage from '../pages/RegisterPage';
import ResetPasswordPage from '../pages/ResetPasswordPage';
import ReviewQueuePage from '../pages/ReviewQueuePage';
import TemplatesPage from '../pages/TemplatesPage';
import VerifyEmailPage from '../pages/VerifyEmailPage';
import WorkspaceDetailPage from '../pages/WorkspaceDetailPage';
import WorkspacesPage from '../pages/WorkspacesPage';

/**
 * Centralised route table. New pages should be added here and only here.
 */
export function AppRoutes() {
  return (
    <AppLayout>
      <RRoutes>
        {/* Public */}
        <Route path="/" element={<HomePage />} />
        <Route path="/about" element={<AboutPage />} />

        {/* Guest-only (logged-out users) */}
        <Route
          path="/login"
          element={
            <GuestRoute>
              <LoginPage />
            </GuestRoute>
          }
        />
        <Route
          path="/register"
          element={
            <GuestRoute>
              <RegisterPage />
            </GuestRoute>
          }
        />
        <Route
          path="/verify-email"
          element={
            <GuestRoute>
              <VerifyEmailPage />
            </GuestRoute>
          }
        />
        <Route
          path="/forgot-password"
          element={
            <GuestRoute>
              <ForgotPasswordPage />
            </GuestRoute>
          }
        />
        <Route
          path="/reset-password"
          element={
            <GuestRoute>
              <ResetPasswordPage />
            </GuestRoute>
          }
        />
        <Route
          path="/auth/callback"
          element={
            <GuestRoute>
              <OAuthCallbackPage />
            </GuestRoute>
          }
        />

        {/* Protected */}
        <Route
          path="/dashboard"
          element={
            <ProtectedRoute>
              <DashboardPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/profile"
          element={
            <ProtectedRoute>
              <ProfilePage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/workspaces"
          element={
            <ProtectedRoute>
              <WorkspacesPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/workspaces/:workspaceId"
          element={
            <ProtectedRoute>
              <WorkspaceDetailPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/workspaces/:workspaceId/documents"
          element={
            <ProtectedRoute>
              <DocumentsListPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/workspaces/:workspaceId/documents/new"
          element={
            <ProtectedRoute>
              <NewDocumentPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/workspaces/:workspaceId/documents/:documentId"
          element={
            <ProtectedRoute>
              <DocumentDetailPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/workspaces/:workspaceId/templates"
          element={
            <ProtectedRoute>
              <TemplatesPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/workspaces/:workspaceId/drift"
          element={
            <ProtectedRoute>
              <DriftAlertsPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/workspaces/:workspaceId/knowledge"
          element={
            <ProtectedRoute>
              <KnowledgeBasePage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/workspaces/:workspaceId/health"
          element={
            <ProtectedRoute>
              <HealthDashboardPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/workspaces/:workspaceId/reviews"
          element={
            <ProtectedRoute>
              <ReviewQueuePage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/admin/users"
          element={
            <ProtectedRoute>
              <AdminUsersPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/github/callback"
          element={
            <ProtectedRoute>
              <GithubCallbackPage />
            </ProtectedRoute>
          }
        />

        {/* Catch-all */}
        <Route path="*" element={<NotFoundPage />} />
      </RRoutes>
    </AppLayout>
  );
}