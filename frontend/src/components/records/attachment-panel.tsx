"use client";

import { useQuery } from "@tanstack/react-query";
import { useRef } from "react";
import { Section, useAction } from "@/components/records/parts";
import { FormError } from "@/components/forms/field";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { api } from "@/lib/api";
import type { Attachment } from "@/lib/types";

export function AttachmentPanel({
  entityType,
  entityId,
  canManage,
}: {
  entityType: string;
  entityId: string;
  canManage: boolean;
}) {
  const path = `/attachments?entityType=${entityType}&entityId=${entityId}`;
  const input = useRef<HTMLInputElement>(null);
  const { data = [] } = useQuery({ queryKey: [path], queryFn: () => api.get<Attachment[]>(path) });
  const { run, pending, message } = useAction();

  function upload(file: File) {
    const form = new FormData();
    form.set("file", file);
    run(() => api.upload(path, form).finally(() => input.current && (input.current.value = "")));
  }

  return (
    <Section title="Documents">
      <div className="space-y-3">
        <FormError message={message} />
        {data.length === 0 && <p className="text-sm text-muted-foreground">No documents yet.</p>}
        <ul className="divide-y text-sm">
          {data.map((attachment) => (
            <li key={attachment.id} className="flex items-center justify-between gap-3 py-2">
              <a
                className="truncate font-medium text-primary hover:underline"
                href={`/api/v1/attachments/${attachment.id}/content`}
              >
                {attachment.fileName}
              </a>
              <span className="ml-auto shrink-0 text-muted-foreground">
                {Math.ceil(attachment.sizeBytes / 1024)} KB
              </span>
              {canManage && (
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => run(() => api.delete(`/attachments/${attachment.id}`))}
                >
                  Remove
                </Button>
              )}
            </li>
          ))}
        </ul>
        {canManage && (
          <Input
            ref={input}
            type="file"
            aria-label="Upload a document"
            disabled={pending}
            onChange={(event) => {
              const file = event.target.files?.[0];
              if (file) upload(file);
            }}
          />
        )}
      </div>
    </Section>
  );
}
