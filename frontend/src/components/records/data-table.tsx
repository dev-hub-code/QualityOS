"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";
import {
  flexRender,
  getCoreRowModel,
  useReactTable,
  type ColumnDef,
} from "@tanstack/react-table";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
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
}

const PAGE_SIZE = 25;

/** A server-paginated list. */
export function DataTable<T>({ path, columns, searchLabel, emptyMessage, filter }: DataTableProps<T>) {
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const params = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
  if (search) params.set("q", search);
  const query = `${params}${filter ? `&${filter}` : ""}`;
  const { data, isPending, error } = useQuery({
    queryKey: [path, query],
    queryFn: () => api.get<PageResponse<T>>(`${path}?${query}`),
    placeholderData: keepPreviousData,
  });

  // eslint-disable-next-line react-hooks/incompatible-library -- nothing here is memoized
  const table = useReactTable({
    data: data?.items ?? [],
    columns,
    getCoreRowModel: getCoreRowModel(),
    manualPagination: true,
    pageCount: data?.totalPages ?? 0,
  });

  return (
    <div className="space-y-3">
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
      {error && <p className="text-sm text-destructive">{error.message}</p>}
      <div className="overflow-x-auto rounded-md border">
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
                <TableCell colSpan={columns.length} className="py-8 text-center text-muted-foreground">
                  {emptyMessage}
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
