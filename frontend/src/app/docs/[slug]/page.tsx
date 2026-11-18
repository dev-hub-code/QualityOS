import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { FlowChart } from "@/components/docs/flow-chart";
import { WorkedExample } from "@/components/docs/worked-example";
import { DOC_EXAMPLES } from "@/lib/doc-examples";
import { DOC_FLOWS } from "@/lib/doc-flows";
import { DOC_PAGES, docPage, type DocSection } from "@/lib/docs";

interface Props {
  params: Promise<{ slug: string }>;
}

export function generateStaticParams() {
  return DOC_PAGES.map(({ slug }) => ({ slug }));
}

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const page = docPage((await params).slug);
  return page ? { title: page.title, description: page.summary } : {};
}

function Section({ section }: { section: DocSection }) {
  return (
    <section className="mt-8">
      <h2 className="text-xl font-semibold">{section.title}</h2>
      {section.text && <p className="mt-2">{section.text}</p>}
      {section.list && (
        <ul className="mt-2 list-disc space-y-1 pl-5">
          {section.list.map((item) => (
            <li key={item}>{item}</li>
          ))}
        </ul>
      )}
      {section.terms && (
        <dl className="mt-3 divide-y border-y">
          {section.terms.map(([term, meaning]) => (
            <div key={term} className="gap-4 py-2 sm:grid sm:grid-cols-[12rem_1fr]">
              <dt className="font-medium">{term}</dt>
              <dd className="text-muted-foreground">{meaning}</dd>
            </div>
          ))}
        </dl>
      )}
    </section>
  );
}

export default async function DocsTopic({ params }: Props) {
  const page = docPage((await params).slug);
  if (!page) {
    notFound();
  }
  const index = DOC_PAGES.indexOf(page);
  const previous = DOC_PAGES[index - 1];
  const next = DOC_PAGES[index + 1];
  const examples = DOC_EXAMPLES[page.slug];

  return (
    <article className="max-w-3xl">
      <p className="text-sm font-medium text-primary">{page.group}</p>
      <h1 className="mt-1 text-3xl font-semibold tracking-tight">{page.title}</h1>
      <p className="mt-3 text-lg text-muted-foreground">{page.summary}</p>
      <dl className="mt-5 grid gap-3 glass rounded-2xl p-4 text-sm sm:grid-cols-3">
        <div>
          <dt className="text-muted-foreground">Find it under</dt>
          <dd className="font-medium">{page.menu}</dd>
        </div>
        <div>
          <dt className="text-muted-foreground">Used by</dt>
          <dd className="font-medium">{page.roles}</dd>
        </div>
        <div>
          <dt className="text-muted-foreground">Available on</dt>
          <dd className="font-medium">{page.plan}</dd>
        </div>
      </dl>

      <section className="mt-8" aria-labelledby="workflows">
        <h2 id="workflows" className="text-xl font-semibold">
          {DOC_FLOWS[page.slug].length > 1 ? "Workflows" : "Workflow"}
        </h2>
        <div className="mt-3 space-y-4">
          {DOC_FLOWS[page.slug].map((flow, index) => (
            <FlowChart key={flow.title} flow={flow} fileName={`${page.slug}-${index + 1}`} />
          ))}
        </div>
      </section>

      {page.sections.map((section) => (
        <Section key={section.title} section={section} />
      ))}

      <section className="mt-10" aria-labelledby="worked-examples">
        <h2 id="worked-examples" className="text-2xl font-semibold">
          {examples.length > 1 ? "Worked examples" : "Worked example"}
        </h2>
        <p className="mt-2 max-w-prose text-sm text-muted-foreground">
          Step by step, with who does each step, what ProductionOS does in return, and why. The people, companies and
          figures are made up.
        </p>
        {examples.map((example, index) => (
          <WorkedExample
            key={example.title}
            example={example}
            label={examples.length > 1 ? `Worked example ${index + 1} of ${examples.length}` : undefined}
          />
        ))}
      </section>

      <nav aria-label="Next and previous" className="mt-8 flex justify-between gap-4 border-t pt-4 text-sm">
        {previous ? <Link className="hover:text-primary" href={`/docs/${previous.slug}`}>← {previous.title}</Link> : <span />}
        {next && <Link className="text-right hover:text-primary" href={`/docs/${next.slug}`}>{next.title} →</Link>}
      </nav>
    </article>
  );
}
