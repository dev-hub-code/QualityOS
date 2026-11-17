"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { FormError } from "@/components/forms/field";
import { Button } from "@/components/ui/button";
import { api, ApiError } from "@/lib/api";

const LOGO = "/api/v1/organizations/current/logo";
const MAX_BYTES = 256 * 1024;

/** The current organisation's logo as a page-local URL, or null when it has none. */
function useLogo(tenantId: string | undefined) {
  const { data } = useQuery({
    queryKey: ["organization-logo", tenantId],
    enabled: !!tenantId,
    queryFn: async () => {
      const response = await fetch(LOGO);
      return response.ok ? URL.createObjectURL(await response.blob()) : null;
    },
  });
  useEffect(() => () => {
    if (data) URL.revokeObjectURL(data);
  }, [data]);
  return data ?? null;
}

/** Shown above the organisation's name in the menu. Nothing when it has no logo. */
export function OrganizationLogo({ tenantId, name }: { tenantId: string; name: string }) {
  const logo = useLogo(tenantId);
  if (!logo) return null;
  // A local object URL: there is nothing for the image optimiser to fetch.
  // eslint-disable-next-line @next/next/no-img-element
  return <img src={logo} alt={`${name} logo`} className="max-h-12 max-w-full self-start object-contain" />;
}

/** Settings: upload, replace or remove the logo shown in the app and on the organisation's emails. */
export function OrganizationLogoSettings({ tenantId, canManage }: { tenantId: string; canManage: boolean }) {
  const logo = useLogo(tenantId);
  const queryClient = useQueryClient();
  const input = useRef<HTMLInputElement>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function run(action: () => Promise<unknown>) {
    setPending(true);
    setMessage(null);
    try {
      await action();
      await queryClient.invalidateQueries({ queryKey: ["organization-logo"] });
    } catch (error) {
      setMessage(error instanceof ApiError ? error.message : "Something went wrong. Please try again.");
    } finally {
      setPending(false);
      if (input.current) input.current.value = "";
    }
  }

  function upload(file: File | undefined) {
    if (!file) return;
    if (file.size > MAX_BYTES) {
      setMessage("The logo must be 256 KB or smaller.");
      return;
    }
    const form = new FormData();
    form.append("file", file);
    void run(() => api.upload("/organizations/current/logo", form));
  }

  return (
    <section className="space-y-4">
      <h2 className="text-base font-semibold text-[#1b2a55]">Logo</h2>
      <p className="text-sm text-muted-foreground">
        Shown in the menu, at the top of every email sent for your organisation, and on your PDF reports. PNG or JPEG, up to 256 KB.
      </p>
      {logo ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img src={logo} alt="Organisation logo" className="max-h-16 max-w-60 rounded border bg-white object-contain p-2" />
      ) : (
        <p className="text-sm">No logo yet.</p>
      )}
      {canManage && (
        <div className="flex flex-wrap gap-2">
          <input
            ref={input}
            type="file"
            accept="image/png,image/jpeg"
            aria-label="Logo file"
            className="hidden"
            onChange={(event) => upload(event.target.files?.[0])}
          />
          <Button variant="outline" disabled={pending} onClick={() => input.current?.click()}>
            {logo ? "Replace logo" : "Upload logo"}
          </Button>
          {logo && (
            <Button variant="outline" disabled={pending} onClick={() => run(() => api.delete("/organizations/current/logo"))}>
              Remove logo
            </Button>
          )}
        </div>
      )}
      <FormError message={message} />
    </section>
  );
}
