import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";

/** A state or union territory. {@code gstCode} is what a GSTIN registered there starts with. */
export interface PlaceState {
  gstCode: string;
  name: string;
  kind: "STATE" | "UNION_TERRITORY";
}

/** The state a typed name means, ignoring case and spaces at the ends; undefined if none. */
export function findState(states: readonly PlaceState[], name: unknown): PlaceState | undefined {
  if (typeof name !== "string") return undefined;
  const wanted = name.trim().toLowerCase();
  return wanted ? states.find((state) => state.name.toLowerCase() === wanted) : undefined;
}

// The lists change only with a release, so they are fetched once per visit.
const ONCE = { staleTime: Infinity, gcTime: Infinity } as const;

export function useStates(enabled: boolean) {
  return useQuery({
    queryKey: ["/places/states"],
    queryFn: () => api.get<PlaceState[]>("/places/states"),
    enabled,
    ...ONCE,
  });
}

/** The main cities of a state, once one is chosen. */
export function useCities(gstCode: string | undefined) {
  return useQuery({
    queryKey: ["/places/states", gstCode, "cities"],
    queryFn: () => api.get<string[]>(`/places/states/${gstCode}/cities`),
    enabled: gstCode !== undefined,
    ...ONCE,
  });
}
