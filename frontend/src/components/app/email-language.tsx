"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { FormError } from "@/components/forms/field";
import { Label } from "@/components/ui/label";
import { useMe } from "@/hooks/use-me";
import { api } from "@/lib/api";

export const EMAIL_LANGUAGES = [
  { value: "en", label: "English" },
  { value: "hi", label: "हिन्दी (Hindi)" },
  { value: "mr", label: "मराठी (Marathi)" },
];

export function EmailLanguage() {
  const queryClient = useQueryClient();
  const { data: me } = useMe();
  const update = useMutation({
    mutationFn: (language: string) =>
      api.put("/auth/me/email-language", { language: language === "" ? null : language }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: ["me"] }),
  });
  if (!me) return null;
  return (
    <section className="space-y-3">
      <h2 className="font-medium">Email language</h2>
      <FormError message={update.error?.message} />
      <Label htmlFor="email-language" className="font-normal">
        Send my emails in
      </Label>
      <select
        id="email-language"
        className="h-9 w-full rounded-md border bg-background px-3 text-sm sm:w-72"
        value={me.user.language ?? ""}
        disabled={update.isPending}
        onChange={(event) => update.mutate(event.target.value)}
      >
        <option value="">Organisation default</option>
        {EMAIL_LANGUAGES.map(({ value, label }) => (
          <option key={value} value={value}>
            {label}
          </option>
        ))}
      </select>
    </section>
  );
}
