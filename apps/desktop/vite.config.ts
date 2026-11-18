import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: { host: '127.0.0.1', port: 5173, strictPort: true },
  // Unit tests live next to the code; Playwright owns e2e/.
  test: { include: ['src/**/*.test.{ts,tsx}'], setupFiles: ['src/test-setup.ts'] },
});
