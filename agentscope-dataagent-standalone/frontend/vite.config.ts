import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  base: process.env.DATAAGENT_BASE_PATH || '/',
  plugins: [
    react(),
    {
      name: 'paint-opening-before-stylesheet',
      apply: 'build',
      transformIndexHtml: {
        order: 'post',
        handler(html) {
          // Vite puts entry CSS in the head. Keep it parser-discovered, after the opening markup,
          // so the browser can paint that markup while the application stylesheet downloads.
          const styles: string[] = [];
          const page = html.replace(/^[ \t]*<link\b[^>]*\brel="stylesheet"[^>]*>\r?\n/gm, line => {
            styles.push(line);
            return '';
          });
          return page.replace(/^[ \t]*<\/body>/m, closingTag => styles.join('') + closingTag);
        },
      },
    },
  ],
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
