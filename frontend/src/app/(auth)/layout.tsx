export default function AuthLayout({ children }: LayoutProps<"/">) {
  return (
    <main className="mx-auto flex w-full max-w-sm flex-1 flex-col justify-center gap-6 px-4 py-12">
      <header className="space-y-1">
        <p className="font-heading text-2xl font-semibold tracking-tight text-primary">QualityOS</p>
        <p className="text-sm text-muted-foreground">
          Digital Quality. Traceable Products. Confident Manufacturing.
        </p>
      </header>
      {children}
    </main>
  );
}
