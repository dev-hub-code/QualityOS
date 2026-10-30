export interface OrganizationSummary {
  id: string;
  name: string;
}

export interface Me {
  user: { id: string; email: string; fullName: string };
  tenantId: string;
  onboarded: boolean;
  permissions: string[];
  organizations: OrganizationSummary[];
}

export interface Organization {
  id: string;
  name: string;
  legalName: string | null;
  gstin: string | null;
  industry: string | null;
  countryCode: string;
  timezone: string;
  currency: string;
  locale: string;
  address: string | null;
  onboarded: boolean;
  version: number;
}

export interface Role {
  id: string;
  code: string;
  name: string;
  description: string | null;
  system: boolean;
  permissions: string[];
}

export interface Permission {
  code: string;
  module: string;
  description: string;
}

export interface Member {
  membershipId: string;
  userId: string;
  email: string;
  fullName: string;
  status: "ACTIVE" | "INACTIVE";
  roles: { id: string; code: string; name: string }[];
}
