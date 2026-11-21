import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",
  cacheComponents: true,
  async redirects() {
    return [{ source: "/defect-categories", destination: "/categories", permanent: false }];
  },
  partialPrefetching: true,
  experimental: {
    // The signed-in pages render only after the session loads in the browser (see AppShell), so a prerender never
    // reaches them and the default validation reports each one as a dropped segment. Validate only segments that
    // opt in with `export const instant`.
    instantInsights: { validationLevel: "manual-warning" },
  },
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
};

export default nextConfig;
