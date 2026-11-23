import react from "@vitejs/plugin-react";
import { defineConfig, loadEnv } from "vite";

// In development and `vite preview`, /api is forwarded to msika-api (change it with MSIKA_API_URL).
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, ".", "");
  const proxy = { "/api": { target: env.MSIKA_API_URL || "http://localhost:8080", changeOrigin: true } };
  return {
    plugins: [react()],
    server: { port: 5173, proxy },
    preview: { port: 5173, proxy },
  };
});
