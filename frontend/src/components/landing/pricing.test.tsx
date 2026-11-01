import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Pricing, type PublicPlan } from "./pricing";

const STARTER: PublicPlan = {
  code: "STARTER", name: "Starter", description: "Basic", monthlyPrice: 3111, annualPrice: 31110, maxUsers: 1,
  maxFacilities: 1, features: [], trialDays: 14, featureNames: [],
};
const ENTERPRISE: PublicPlan = {
  ...STARTER, code: "ENTERPRISE", name: "Enterprise", monthlyPrice: null, annualPrice: null, maxUsers: null,
  maxFacilities: null, features: ["ADVANCED"], featureNames: ["Traceability, recalls, calibration and audits"],
};

describe("Pricing", () => {
  it("shows whatever price the plan holds", () => {
    render(<Pricing plans={[STARTER]} />);
    expect(screen.getByText(/₹3,111/)).toBeInTheDocument();
    expect(screen.getByText("or ₹31,110 a year")).toBeInTheDocument();
  });

  it("shows an agreed plan as custom, with no limits", () => {
    render(<Pricing plans={[ENTERPRISE]} />);
    expect(screen.getByText("Custom")).toBeInTheDocument();
    expect(screen.getByText("Unlimited users")).toBeInTheDocument();
    expect(screen.getByText("Unlimited facilities")).toBeInTheDocument();
    expect(screen.getByText("Traceability, recalls, calibration and audits")).toBeInTheDocument();
  });

  it("shows the trial length it is given", () => {
    render(<Pricing plans={[{ ...STARTER, trialDays: 30 }]} />);
    expect(screen.getByRole("link", { name: "Start a 30-day trial" })).toHaveAttribute("href", "/register");
  });

  it("offers no invented figure when prices cannot be read", () => {
    render(<Pricing plans={null} />);
    expect(screen.getByText(/Prices cannot be shown right now/)).toBeInTheDocument();
    expect(screen.queryByText(/₹/)).not.toBeInTheDocument();
  });
});
