"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { PageHeader, QueryState, StatusBadge } from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { api } from "@/lib/api";
import type { PlatformJob, PlatformJobRun } from "@/lib/types";

const time = (value: string) => new Date(value).toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" });

function every(seconds: number) {
  if (seconds < 3600) return `${Math.round(seconds / 60)} min`;
  return `${Math.round(seconds / 3600)} h`;
}

function Runs({ job, onClose }: { job: string; onClose: () => void }) {
  const path = `/platform/jobs/${job}/runs`;
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<PlatformJobRun[]>(path) });
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Recent runs of {job}</DialogTitle>
        </DialogHeader>
        {!data ? (
          <QueryState error={error} />
        ) : data.length === 0 ? (
          <p className="text-sm text-muted-foreground">This job has not run yet.</p>
        ) : (
          <div className="max-h-96 overflow-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Started</TableHead>
                  <TableHead>Result</TableHead>
                  <TableHead>Duration</TableHead>
                  <TableHead>Organisations</TableHead>
                  <TableHead>Records</TableHead>
                  <TableHead>Failures</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.map((run) => (
                  <TableRow key={run.startedAt}>
                    <TableCell className="whitespace-nowrap">{time(run.startedAt)}</TableCell>
                    <TableCell><StatusBadge status={run.status} /></TableCell>
                    <TableCell>{run.durationMs} ms</TableCell>
                    <TableCell>{run.tenantsProcessed}</TableCell>
                    <TableCell>{run.recordsProcessed}</TableCell>
                    <TableCell>{run.failureCount}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

export default function PlatformJobsPage() {
  const [viewing, setViewing] = useState<string | null>(null);
  const { data, error } = useQuery({
    queryKey: ["/platform/jobs"],
    queryFn: () => api.get<PlatformJob[]>("/platform/jobs"),
  });
  return (
    <div className="space-y-6">
      <PageHeader title="Jobs" subtitle="Background work. A job is stale once it has missed three runs in a row." />
      {!data ? (
        <QueryState error={error} />
      ) : (
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Job</TableHead>
                <TableHead>Every</TableHead>
                <TableHead>Last run</TableHead>
                <TableHead>Result</TableHead>
                <TableHead>Failed in 24 h</TableHead>
                <TableHead className="text-right">History</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.map((job) => (
                <TableRow key={job.name}>
                  <TableCell className="font-medium">{job.name}</TableCell>
                  <TableCell>{every(job.intervalSeconds)}</TableCell>
                  <TableCell className="whitespace-nowrap">{job.lastStartedAt ? time(job.lastStartedAt) : "Not yet"}</TableCell>
                  <TableCell>
                    {job.stale ? <StatusBadge status="STALE" /> : job.lastStatus ? <StatusBadge status={job.lastStatus} /> : "None"}
                  </TableCell>
                  <TableCell>{job.failuresToday}</TableCell>
                  <TableCell className="text-right">
                    <Button size="sm" variant="outline" onClick={() => setViewing(job.name)}>Runs</Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      {viewing && <Runs job={viewing} onClose={() => setViewing(null)} />}
    </div>
  );
}
