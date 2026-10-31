import Link from "next/link";
import { DOC_GROUPS, DOC_PAGES } from "@/lib/docs";

export default function DocsHome() {
  return (
    <>
      <h1 className="text-3xl font-semibold tracking-tight">Documentation</h1>
      <p className="mt-3 max-w-2xl text-muted-foreground">
        How each part of QualityOS works, who uses it, and a worked example. The people, companies and figures in the
        examples are made up.
      </p>
      {DOC_GROUPS.map((group) => (
        <section key={group} className="mt-8">
          <h2 className="text-lg font-semibold">{group}</h2>
          <div className="mt-3 grid gap-3 sm:grid-cols-2">
            {DOC_PAGES.filter((page) => page.group === group).map((page) => (
              <Link key={page.slug} href={`/docs/${page.slug}`} className="rounded-lg border p-4 hover:border-primary">
                <h3 className="font-medium">{page.title}</h3>
                <p className="mt-1 text-sm text-muted-foreground">{page.summary}</p>
              </Link>
            ))}
          </div>
        </section>
      ))}
    </>
  );
}
