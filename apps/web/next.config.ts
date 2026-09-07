import type { NextConfig } from "next";

/**
 * The API is reached through this app's own origin.
 *
 * Everything under /api is proxied to the backend, so the browser only ever talks to one origin.
 * That is what lets the session cookie be SameSite=Lax and the CSRF cookie be read by the client
 * without any cross-origin policy at all — there is no CORS configuration anywhere in this project,
 * and a wildcard one with credentials would be exactly the hole this avoids.
 */
const API_INTERNAL_URL = process.env.API_INTERNAL_URL ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  reactStrictMode: true,
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${API_INTERNAL_URL}/api/:path*` }];
  },
};

export default nextConfig;
