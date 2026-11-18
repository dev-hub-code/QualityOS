import Link from "next/link";
import { FlowChart } from "@/components/docs/flow-chart";
import { DOC_FLOWS, OVERVIEW_FLOW } from "@/lib/doc-flows";
import { DOC_GROUPS, DOC_PAGES } from "@/lib/docs";

export const metadata = { title: "Workflows" };

export default function WorkflowsPage() {
  return (
    <>
      <p className="text-sm">
        <Link href="/docs" className="text-primary hover:underline">
          Documentation
        </Link>
      </p>
      <h1 className="mt-2 text-3xl font-semibold tracking-tight">Every workflow</h1>
      <p className="mt-3 max-w-2xl text-muted-foreground">
        Each part of ProductionOS as a flowchart: the steps down the left, who does them, and at each decision what
        happens either way. Open a topic for the detail and a worked example.
      </p>
      <nav aria-label="Jump to a topic" className="mt-6 flex flex-wrap gap-2 print:hidden">
        {DOC_PAGES.map((page) => (
          <a key={page.slug} href={`#${page.slug}`} className="rounded-full border px-3 py-1 text-sm hover:bg-muted">
            {page.title}
          </a>
        ))}
      </nav>
      <section className="mt-8 max-w-3xl">
        <h2 className="text-xl font-semibold">The big picture</h2>
        <div className="mt-3">
          <FlowChart flow={OVERVIEW_FLOW} fileName="overview" />
        </div>
      </section>
      {DOC_GROUPS.map((group) => (
        <section key={group} className="mt-10">
          <h2 className="text-2xl font-semibold">{group}</h2>
          {DOC_PAGES.filter((page) => page.group === group).map((page) => (
            <section key={page.slug} id={page.slug} className="mt-6 max-w-3xl scroll-mt-20">
              <h3 className="text-lg font-semibold">
                <Link href={`/docs/${page.slug}`} className="hover:underline">
                  {page.title}
                </Link>
              </h3>
              <p className="mt-1 text-sm text-muted-foreground">{page.summary}</p>
              <div className="mt-3 space-y-4">
                {DOC_FLOWS[page.slug].map((flow, index) => (
                  <FlowChart key={flow.title} flow={flow} fileName={`${page.slug}-${index + 1}`} />
                ))}
              </div>
            </section>
          ))}
        </section>
      ))}
    </>
  );
}
