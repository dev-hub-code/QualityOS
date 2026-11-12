"use client";

import { useQuery } from "@tanstack/react-query";
import { useRef } from "react";
import { Section, useAction } from "@/components/records/parts";
import { FormError } from "@/components/forms/field";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { api } from "@/lib/api";
import type { Attachment } from "@/lib/types";

/** A record's files, with an upload control for those who may add them. */
export function AttachmentPanel(props: { entityType: string; entityId: string; canManage: boolean }) {
  return (
    <Section title="Documents">
      <AttachmentList {...props} />
    </Section>
  );
}

/**
 * The files themselves, for placing inside a larger record, such as each action of a CAPA.
 *
 * @param uploadLabel names the upload control, which must differ when a page shows several lists
 * @param emptyMessage shown when there are no files
 */
export function AttachmentList({
  entityType,
  entityId,
  canManage,
  uploadLabel = "Upload a document",
  emptyMessage = "No documents yet.",
}: {
  entityType: string;
  entityId: string;
  canManage: boolean;
  uploadLabel?: string;
  emptyMessage?: string;
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
    <div className="space-y-3">
      <FormError message={message} />
      {data.length === 0 && <p className="text-sm text-muted-foreground">{emptyMessage}</p>}
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
          aria-label={uploadLabel}
          disabled={pending}
          onChange={(event) => {
            const file = event.target.files?.[0];
            if (file) upload(file);
          }}
        />
      )}
    </div>
  );
}
