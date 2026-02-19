import type { TimeWindow, CreateTimeWindowRequest } from "../types/TimeWindow";

const BASE_URL = "http://localhost:8080/api/time-windows";

async function http<T>(input: RequestInfo, init?: RequestInit): Promise<T> {
  const res = await fetch(input, init);
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`HTTP ${res.status}: ${text || res.statusText}`);
  }
  // DELETE/PATCH mogą zwracać pusty body
  const contentType = res.headers.get("content-type") || "";
  if (!contentType.includes("application/json")) {
    return undefined as T;
  }
  return (await res.json()) as T;
}

export const timeWindowsApi = {
  list: () => http<TimeWindow[]>(BASE_URL),

  create: (req: CreateTimeWindowRequest) =>
    http<void>(BASE_URL, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(req),
    }),

  setEnabled: (windowId: string, value: boolean) =>
    http<void>(`${BASE_URL}/${windowId}/enabled?value=${value}`, {
      method: "PATCH",
    }),

  remove: (windowId: string) =>
    http<void>(`${BASE_URL}/${windowId}`, {
      method: "DELETE",
    }),
};
