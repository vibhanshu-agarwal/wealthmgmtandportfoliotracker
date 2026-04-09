import { auth } from "@/auth";

// Use NextAuth's `auth` export directly as Next.js middleware.
// Route protection logic lives in the `authorized` callback in auth.config.ts.
export default auth;

export const config = {
  // Match all routes except Next.js internals and static assets.
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
