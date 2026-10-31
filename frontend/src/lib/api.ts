export interface FieldViolation {
  field: string;
  message: string;
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly violations: FieldViolation[] = [],
  ) {
    super(message);
  }
}

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

async function send<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    ...init,
    // The browser sets the multipart boundary itself.
    headers: typeof init?.body === "string" ? { "content-type": "application/json" } : undefined,
  });
  if (response.status === 204) {
    return undefined as T;
  }
  const payload = await response.json().catch(() => null);
  if (!response.ok) {
    if (response.status === 401 && url.startsWith("/api/v1") && typeof window !== "undefined") {
      hardNavigate("/login");
    }
    throw new ApiError(
      response.status,
      payload?.error?.code ?? "REQUEST_FAILED",
      payload?.error?.message ?? "Something went wrong. Please try again.",
      payload?.error?.violations,
    );
  }
  return payload?.data as T;
}

export const api = {
  get: <T>(path: string) => send<T>(`/api/v1${path}`),
  post: <T>(path: string, body?: unknown) =>
    send<T>(`/api/v1${path}`, { method: "POST", body: JSON.stringify(body ?? {}) }),
  put: <T>(path: string, body: unknown) =>
    send<T>(`/api/v1${path}`, { method: "PUT", body: JSON.stringify(body) }),
  delete: <T>(path: string) => send<T>(`/api/v1${path}`, { method: "DELETE" }),
  upload: <T>(path: string, form: FormData) => send<T>(`/api/v1${path}`, { method: "POST", body: form }),
};

/** Session endpoints handled by the web server itself. */
export const session = {
  post: (path: string, body: unknown) =>
    send<void>(`/api/auth/${path}`, { method: "POST", body: JSON.stringify(body) }),
};

/**
 * Full page load. Used when the session or organisation changes, because it also discards every cached
 * query, which client-side navigation would keep showing to the next user.
 */
export function hardNavigate(path: string) {
  window.location.assign(path);
}
