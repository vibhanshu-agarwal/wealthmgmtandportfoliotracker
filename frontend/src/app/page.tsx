import { redirect } from "next/navigation";

/**
 * Root page — redirects to the dashboard overview.
 * Unauthenticated users will be caught by the (auth) layout once middleware is wired.
 */
export default function RootPage() {
  redirect("/overview");
}
