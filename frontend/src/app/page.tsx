import { redirect } from "next/navigation";

/**
 * Root route — send authenticated users to the rules dashboard.
 * Unauthenticated users are intercepted by middleware and sent to /login.
 */
export default function Home() {
  redirect("/rules");
}
