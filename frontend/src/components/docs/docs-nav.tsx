import Link from "next/link";
import { DOC_GROUPS, DOC_PAGES } from "@/lib/docs";

function Topics() {
  return DOC_GROUPS.map((group) => (
    <div key={group}>
      <h2 className="text-xs font-semibold text-muted-foreground">{group}</h2>
      <ul className="mt-1 mb-4">
        {DOC_PAGES.filter((page) => page.group === group).map((page) => (
          <li key={page.slug}>
            <Link href={`/docs/${page.slug}`} className="block py-1 text-sm hover:text-primary">
              {page.title}
            </Link>
          </li>
        ))}
      </ul>
    </div>
  ));
}

export function DocsNav() {
  return (
    <>
      <details className="rounded-lg border px-3 py-2 lg:hidden">
        <summary className="cursor-pointer text-sm font-medium">All topics</summary>
        <nav aria-label="Topics, compact" className="mt-3">
          <Topics />
        </nav>
      </details>
      <aside className="hidden lg:block">
        <nav aria-label="Topics" className="sticky top-6 max-h-[calc(100vh-3rem)] overflow-y-auto">
          <Topics />
        </nav>
      </aside>
    </>
  );
}
