import Link from "next/link";
import { buttonVariants } from "@/components/ui/button";

export interface PublicPlan {
  code: string;
  name: string;
  description: string;
  monthlyPrice: number | null;
  annualPrice: number | null;
  maxUsers: number | null;
  maxFacilities: number | null;
  features: string[];
  trialDays: number;
}

const BASE = "Inspections, suppliers, purchasing and receipts";
const FEATURES: Record<string, string> = {
  NCR_CAPA: "NCR and CAPA",
  REPORTS: "Dashboards and reports",
  ADVANCED: "Traceability, recalls, calibration and audits",
};

const rupees = (value: number) => `₹${value.toLocaleString("en-IN", { maximumFractionDigits: 2 })}`;

const count = (value: number | null, noun: string) =>
  value === null ? `Unlimited ${noun}s` : `${value.toLocaleString("en-IN")} ${noun}${value === 1 ? "" : "s"}`;

function Plan({ plan }: { plan: PublicPlan }) {
  return (
    <li className="flex flex-col border-t-2 border-foreground pt-4">
      <h3 className="text-lg font-semibold">{plan.name}</h3>
      <p className="mt-1 min-h-10 text-sm text-muted-foreground">{plan.description}</p>
      {plan.monthlyPrice === null ? (
        <p className="mt-4 text-3xl font-semibold tracking-tight">Custom</p>
      ) : (
        <p className="mt-4 text-3xl font-semibold tracking-tight">
          {rupees(plan.monthlyPrice)}
          <span className="text-sm font-normal text-muted-foreground"> a month</span>
        </p>
      )}
      <p className="mt-1 h-5 text-sm text-muted-foreground">
        {plan.annualPrice !== null && `or ${rupees(plan.annualPrice)} a year`}
      </p>
      <ul className="mt-4 space-y-1.5 text-sm">
        <li>{count(plan.maxUsers, "user")}</li>
        <li>{count(plan.maxFacilities, "facility").replace("facilitys", "facilities")}</li>
        <li>{BASE}</li>
        {plan.features.map((feature) => (
          <li key={feature}>{FEATURES[feature] ?? feature}</li>
        ))}
      </ul>
    </li>
  );
}

/** Prices come from the plans a platform administrator keeps, and are never written into this page. */
export function Pricing({ plans }: { plans: PublicPlan[] | null }) {
  if (!plans || plans.length === 0) {
    return (
      <p className="text-muted-foreground">
        Prices cannot be shown right now. They are listed on the billing page once you have an account.
      </p>
    );
  }
  const trial = Math.max(...plans.map((plan) => plan.trialDays));
  return (
    <>
      <ul className="grid gap-8 sm:grid-cols-2 lg:grid-cols-4">
        {plans.map((plan) => (
          <Plan key={plan.code} plan={plan} />
        ))}
      </ul>
      <div className="mt-10 flex flex-wrap items-center gap-4">
        {trial > 0 && (
          <Link href="/register" className={buttonVariants({ size: "lg" })}>
            Start a {trial}-day trial
          </Link>
        )}
        <p className="text-sm text-muted-foreground">Prices exclude GST. No card is needed to start.</p>
      </div>
    </>
  );
}
