import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const apiBaseUrl = env.VITE_API_BASE_URL ?? 'http://localhost:8080/api/v1';

  return {
    plugins: [react()],
    define: {
      __API_BASE_URL__: JSON.stringify(apiBaseUrl),
    },
    server: {
      host: '0.0.0.0',
      port: Number(env.FRONTEND_PORT ?? 5173),
    },
    preview: {
      host: '0.0.0.0',
      port: 5173,
    },
  };
});