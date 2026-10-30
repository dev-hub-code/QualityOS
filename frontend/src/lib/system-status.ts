export type SystemState = "operational" | "not-ready" | "unreachable";

export interface SystemStatusReport {
  state: SystemState;
  version: string | null;
}

const REQUEST_TIMEOUT_MS = 5_000;

/** Runs on the server: the backend is not exposed to browsers. */
export async function fetchSystemStatus(backendUrl: string): Promise<SystemStatusReport> {
  try {
    const readiness = await fetch(`${backendUrl}/actuator/health/readiness`, {
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
    if (!readiness.ok) {
      return { state: "not-ready", version: null };
    }

    const info = await fetch(`${backendUrl}/api/v1/system/info`, {
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
    const version = info.ok ? (await info.json()).data.version : null;
    return { state: "operational", version };
  } catch {
    return { state: "unreachable", version: null };
  }
}
