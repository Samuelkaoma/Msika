import react from "@vitejs/plugin-react";
import { defineConfig, loadEnv } from "vite";

// The console calls msika-api under /api and zamsim under /zamsim. In development and `vite preview`, Vite forwards
// them; the targets can be changed with MSIKA_API_URL and ZAMSIM_URL (in the environment or a .env file).
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, ".", "");
  const proxy = {
    "/api": { target: env.MSIKA_API_URL || "http://localhost:8080", changeOrigin: true },
    "/zamsim": {
      target: env.ZAMSIM_URL || "http://localhost:8081",
      changeOrigin: true,
      rewrite: (path: string) => path.replace(/^\/zamsim/, ""),
    },
  };
  return {
    plugins: [react()],
    server: { port: 5174, proxy },
    preview: { port: 5174, proxy },
  };
});
