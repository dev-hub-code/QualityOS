import { fetchSystemStatus } from "@/lib/system-status";

export async function GET() {
  const backendUrl = process.env.BACKEND_URL ?? "http://localhost:8080";
  return Response.json(await fetchSystemStatus(backendUrl));
}
