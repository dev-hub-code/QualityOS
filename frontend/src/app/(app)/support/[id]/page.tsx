"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams } from "next/navigation";
import { useState } from "react";
import { FormError } from "@/components/forms/field";
import { PageHeader, QueryState, Section, StatusBadge, useRefresh } from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { api } from "@/lib/api";
import type { CustomerTicket, SupportMessage } from "@/lib/types";

const time = (value: string) => new Date(value).toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" });

export default function SupportRequestPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/support/tickets/${id}`;
  const refresh = useRefresh();
  const [reply, setReply] = useState("");
  const [sending, setSending] = useState(false);
  const [failure, setFailure] = useState("");
  const { data: ticket, error } = useQuery({ queryKey: [path], queryFn: () => api.get<CustomerTicket>(path) });
  const { data: messages, error: messagesError } = useQuery({
    queryKey: [`${path}/messages`],
    queryFn: () => api.get<SupportMessage[]>(`${path}/messages`),
  });
  if (!ticket) return <QueryState error={error} />;
  const closed = ticket.status === "CLOSED";
  return (
    <div className="max-w-3xl space-y-6">
      <PageHeader
        title={ticket.subject}
        subtitle={
          <span className="flex flex-wrap items-center gap-2">
            <StatusBadge status={ticket.status} />
            Raised by {ticket.openedBy} on {time(ticket.createdAt)}
          </span>
        }
      />
      <Section title="Messages">
        {!messages ? (
          <QueryState error={messagesError} />
        ) : (
          <ul className="space-y-3">
            {messages.map((message) => (
              <li
                key={message.id}
                className={`rounded-md border p-3 text-sm ${message.fromSupport ? "bg-blue-50/60" : ""}`}
              >
                <p className="text-muted-foreground">
                  {message.author}, {time(message.createdAt)}
                </p>
                <p className="whitespace-pre-wrap">{message.body}</p>
              </li>
            ))}
          </ul>
        )}
      </Section>
      {closed ? (
        <p className="text-sm text-muted-foreground">This request is closed. Raise a new one if you need more help.</p>
      ) : (
        <form
          className="space-y-2"
          onSubmit={async (event) => {
            event.preventDefault();
            setFailure("");
            setSending(true);
            try {
              await api.post(`${path}/replies`, { body: reply });
              setReply("");
              await refresh();
            } catch (cause) {
              setFailure(cause instanceof Error ? cause.message : "Something went wrong.");
            } finally {
              setSending(false);
            }
          }}
        >
          <Label htmlFor="support-reply">Your reply</Label>
          <textarea
            id="support-reply"
            className="min-h-24 w-full rounded-xl border bg-white/70 px-3 py-2 text-sm"
            value={reply}
            onChange={(event) => setReply(event.target.value)}
            required
          />
          <FormError message={failure} />
          <Button type="submit" disabled={sending || reply.trim().length === 0}>
            Send reply
          </Button>
        </form>
      )}
    </div>
  );
}
