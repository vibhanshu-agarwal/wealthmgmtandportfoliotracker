export { auth as default } from "@/auth";

// Route protection logic lives in the `authorized` callback in auth.config.ts.

export const config = {
  // Match all routes except Next.js internals and static assets.
  // /login and /api/auth/* are handled by the authorized callback in auth.config.ts.
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
