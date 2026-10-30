import { redirect } from "next/navigation";

/** Unreachable in normal use: the proxy sends this path to /login or /dashboard. */
export default function Home() {
  redirect("/login");
}
