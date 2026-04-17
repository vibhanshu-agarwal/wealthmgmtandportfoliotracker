import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Static export for S3 hosting; `npm run build` emits `frontend/out/`.
  output: "export",
};

export default nextConfig;
