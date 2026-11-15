"use client";

import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  flexRender,
  getCoreRowModel,
  useReactTable,
  type ColumnDef,
} from "@tanstack/react-table";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { api, type PageResponse } from "@/lib/api";

interface DataTableProps<T> {
  /** Also the query key, so that a mutation can invalidate the list. */
  path: string;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- columns hold different value types
  columns: ColumnDef<T, any>[];
  searchLabel?: string;
  emptyMessage: string;
  /** Extra query string, such as `purchaseOrderId=…`. */
  filter?: string;
  /** Lets the caller see archived records and restore them, at `${path}/{id}/restore`. */
  restorable?: boolean;
}

const PAGE_SIZE = 25;

/** A server-paginated list. */
export function DataTable<T>({ path, columns, searchLabel, emptyMessage, filter, restorable }: DataTableProps<T>) {
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const [archived, setArchived] = useState(false);
  const [restoreError, setRestoreError] = useState<string | null>(null);
  const queryClient = useQueryClient();
  const params = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
  if (search) params.set("q", search);
  if (archived) params.set("archived", "true");
  const query = `${params}${filter ? `&${filter}` : ""}`;
  const { data, isPending, error } = useQuery({
    queryKey: [path, query],
    queryFn: () => api.get<PageResponse<T>>(`${path}?${query}`),
    placeholderData: keepPreviousData,
  });

  const restore = async (id: string) => {
    setRestoreError(null);
    try {
      await api.post(`${path}/${id}/restore`);
      await queryClient.invalidateQueries({ queryKey: [path] });
    } catch (failure) {
      setRestoreError(failure instanceof Error ? failure.message : "It could not be restored.");
    }
  };
  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- columns hold different value types
  const shown: ColumnDef<T, any>[] = archived
    ? [
        ...columns,
        {
          id: "restore",
          header: "",
          cell: ({ row }) => (
            <Button variant="outline" size="sm" onClick={() => restore((row.original as { id: string }).id)}>
              Restore
            </Button>
          ),
        },
      ]
    : columns;

  // eslint-disable-next-line react-hooks/incompatible-library -- nothing here is memoized
  const table = useReactTable({
    data: data?.items ?? [],
    columns: shown,
    getCoreRowModel: getCoreRowModel(),
    manualPagination: true,
    pageCount: data?.totalPages ?? 0,
  });

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-4">
        {searchLabel && (
          <Input
            aria-label={searchLabel}
            placeholder={searchLabel}
            className="max-w-sm"
            value={search}
            onChange={(event) => {
              setSearch(event.target.value);
              setPage(0);
            }}
          />
        )}
        {restorable && (
          <Label className="flex items-center gap-2 text-sm font-normal">
            <input
              type="checkbox"
              checked={archived}
              onChange={(event) => {
                setArchived(event.target.checked);
                setPage(0);
              }}
            />
            Show archived
          </Label>
        )}
      </div>
      {restoreError && <p className="text-sm text-destructive">{restoreError}</p>}
      {error && <p className="text-sm text-destructive">{error.message}</p>}
      <div className="glass overflow-x-auto rounded-2xl">
        <Table>
          <TableHeader>
            {table.getHeaderGroups().map((group) => (
              <TableRow key={group.id}>
                {group.headers.map((header) => (
                  <TableHead key={header.id}>
                    {flexRender(header.column.columnDef.header, header.getContext())}
                  </TableHead>
                ))}
              </TableRow>
            ))}
          </TableHeader>
          <TableBody>
            {table.getRowModel().rows.map((row) => (
              <TableRow key={row.id}>
                {row.getVisibleCells().map((cell) => (
                  <TableCell key={cell.id}>{flexRender(cell.column.columnDef.cell, cell.getContext())}</TableCell>
                ))}
              </TableRow>
            ))}
            {!isPending && data?.items.length === 0 && (
              <TableRow>
                <TableCell colSpan={shown.length} className="py-8 text-center text-muted-foreground">
                  {archived ? "Nothing is archived." : emptyMessage}
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </div>
      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-end gap-3 text-sm">
          <span className="text-muted-foreground">
            Page {data.page + 1} of {data.totalPages}
          </span>
          <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}>
            Previous
          </Button>
          <Button
            variant="outline"
            size="sm"
            disabled={page + 1 >= data.totalPages}
            onClick={() => setPage(page + 1)}
          >
            Next
          </Button>
        </div>
      )}
    </div>
  );
}
