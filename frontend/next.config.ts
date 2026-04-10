import type { NextConfig } from "next";

const nextConfig: NextConfig = {
// Enables the optimized multi-stage Docker build
  output: 'standalone',

  // Proxy /api/* to the Spring Boot backend, but exclude /api/auth/*
  // which is handled locally by the NextAuth route handler.
  async rewrites() {
    return [
      {
        source: "/api/:path((?!auth).*)*",
        destination: "http://localhost:8080/api/:path*",
      },
    ];
  },
};

export default nextConfig;
