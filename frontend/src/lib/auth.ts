import { betterAuth } from "better-auth";
import { Pool } from "pg";

export const auth = betterAuth({
  database: new Pool({
    connectionString: process.env.DATABASE_URL,
  }),
  emailAndPassword: {
    enabled: true,
  },
  session: {
    modelName: "ba_session",
    cookieCache: {
      enabled: true,
      maxAge: 300, // 5 minutes
      strategy: "jwt",
    },
    expiresIn: 60 * 60, // 1 hour (matches current JWT expiry)
  },
  secret: process.env.BETTER_AUTH_SECRET,
  // Table name mapping — use ba_ prefix to avoid conflicts with
  // Flyway-managed tables (users, portfolios, asset_holdings, market_prices)
  user: {
    modelName: "ba_user",
  },
  account: {
    modelName: "ba_account",
  },
  verification: {
    modelName: "ba_verification",
  },
});

export type Session = typeof auth.$Infer.Session;
