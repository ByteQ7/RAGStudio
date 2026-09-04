import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "path";

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src")
    }
  },
  server: {
    // 固定专属端口：被占用时直接报错退出，避免多项目并存时静默漂移导致访问错项目
    port: 51023,
    strictPort: true,
    proxy: {
      "/api": {
        target: "http://localhost:9090",
        changeOrigin: true,
        secure: false
      }
    }
  }
});
