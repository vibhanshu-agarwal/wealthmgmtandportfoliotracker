import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Enables the optimized multi-stage Docker build
  output: 'standalone',
  // Point to the Spring Boot backend (update port as needed)
  async rewrites() {
    return [
      {
        source: "/api/:path*",
        destination: "http://localhost:8080/api/:path*",
      },
    ];
  },
};

export default nextConfig;
