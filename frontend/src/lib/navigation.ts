import {
  Banknote,
  BookOpen,
  Boxes,
  Building,
  Building2,
  ChartColumn,
  ChartNoAxesCombined,
  ClipboardCheck,
  ClipboardList,
  ClipboardX,
  CreditCard,
  Factory,
  FileChartColumn,
  FileCog,
  FilePen,
  Gauge,
  GitBranch,
  Hourglass,
  LayoutDashboard,
  ListChecks,
  ListTodo,
  Package,
  PackageCheck,
  ReceiptText,
  Repeat,
  ScanSearch,
  SearchCheck,
  Send,
  Settings,
  ShieldCheck,
  ShieldEllipsis,
  Siren,
  Tags,
  ToggleRight,
  Truck,
  Undo2,
  Users,
  Workflow,
  type LucideIcon,
  UserCog,
  ScrollText,
  Bell,
  Mail,
  Timer,
  SlidersHorizontal,
  Megaphone,
  LifeBuoy,
  HeartPulse,
} from "lucide-react";

export interface NavItem {
  label: string;
  href: string;
  icon: LucideIcon;
  /** Shown when the user holds any of these; omit for everyone. */
  anyPermission?: string[];
}

export const NAV_ITEMS: NavItem[] = [
  { label: "Dashboard", href: "/dashboard", icon: LayoutDashboard },
  { label: "Suppliers", href: "/suppliers", icon: Truck, anyPermission: ["SUPPLIER_VIEW"] },
  { label: "Materials", href: "/materials", icon: Boxes, anyPermission: ["PRODUCT_VIEW"] },
  { label: "Products", href: "/products", icon: Package, anyPermission: ["PRODUCT_VIEW"] },
  { label: "Purchase orders", href: "/purchase-orders", icon: ClipboardList, anyPermission: ["PURCHASE_VIEW"] },
  { label: "Receipts", href: "/material-receipts", icon: PackageCheck, anyPermission: ["PURCHASE_VIEW"] },
  { label: "Inspections", href: "/inspections", icon: ClipboardCheck, anyPermission: ["INSPECTION_VIEW"] },
  { label: "Templates", href: "/inspection-templates", icon: FileCog, anyPermission: ["TEMPLATE_VIEW"] },
  { label: "NCRs", href: "/ncrs", icon: ClipboardX, anyPermission: ["NCR_VIEW"] },
  { label: "CAPAs", href: "/capas", icon: ListChecks, anyPermission: ["CAPA_VIEW"] },
  { label: "Defect categories", href: "/defect-categories", icon: Tags, anyPermission: ["NCR_VIEW"] },
  { label: "Production", href: "/production-batches", icon: Workflow, anyPermission: ["TRACEABILITY_VIEW"] },
  { label: "Dispatches", href: "/dispatches", icon: Send, anyPermission: ["TRACEABILITY_VIEW"] },
  { label: "Returns", href: "/dispatch-reversals", icon: Undo2, anyPermission: ["TRACEABILITY_VIEW"] },
  { label: "Customers", href: "/customers", icon: Building2, anyPermission: ["TRACEABILITY_VIEW"] },
  { label: "Traceability", href: "/traceability", icon: GitBranch, anyPermission: ["TRACEABILITY_VIEW"] },
  { label: "Serial search", href: "/traceability/serials", icon: ScanSearch, anyPermission: ["TRACEABILITY_VIEW"] },
  { label: "Recalls", href: "/recalls", icon: Siren, anyPermission: ["RECALL_VIEW"] },
  { label: "Calibration", href: "/calibration", icon: Gauge, anyPermission: ["CALIBRATION_VIEW"] },
  { label: "Audits", href: "/audits", icon: SearchCheck, anyPermission: ["AUDIT_VIEW"] },
  { label: "Audit checklists", href: "/audit-checklists", icon: ListTodo, anyPermission: ["AUDIT_VIEW"] },
  { label: "Analytics", href: "/analytics", icon: ChartColumn, anyPermission: ["REPORT_VIEW"] },
  { label: "Reports", href: "/reports", icon: FileChartColumn, anyPermission: ["REPORT_VIEW"] },
  { label: "Corrections", href: "/material-corrections", icon: FilePen, anyPermission: ["TRACEABILITY_VIEW"] },
  { label: "Facilities", href: "/facilities", icon: Factory, anyPermission: ["FACILITY_VIEW"] },
  { label: "Users", href: "/users", icon: Users, anyPermission: ["USER_MANAGE"] },
  { label: "Roles", href: "/roles", icon: ShieldCheck, anyPermission: ["ROLE_MANAGE", "USER_MANAGE"] },
  { label: "Billing", href: "/settings/billing", icon: CreditCard, anyPermission: ["SUBSCRIPTION_MANAGE"] },
  { label: "Settings", href: "/settings", icon: Settings },
  { label: "Documentation", href: "/docs", icon: BookOpen },
  { label: "Control Center", href: "/platform/dashboard", icon: ShieldEllipsis, anyPermission: ["PLATFORM_ADMIN"] },
];

/** Kept apart from the workspace. A page joins this list only once it works. */
export const CONTROL_CENTER_NAV: { heading: string; items: NavItem[] }[] = [
  { heading: "Overview", items: [{ label: "Dashboard", href: "/platform/dashboard", icon: LayoutDashboard }] },
  { heading: "Customers", items: [
      { label: "Organisations", href: "/platform/organisations", icon: Building },
      { label: "Subscriptions", href: "/platform/subscriptions", icon: Repeat },
      { label: "Trials", href: "/platform/trials", icon: Hourglass },
      { label: "Usage", href: "/platform/usage", icon: Gauge },
    ] },
  { heading: "Billing", items: [
      { label: "Plans", href: "/platform/plans", icon: Tags },
      { label: "Invoices", href: "/platform/invoices", icon: ReceiptText },
      { label: "Payments", href: "/platform/payments", icon: Banknote },
      { label: "Analytics", href: "/platform/analytics", icon: ChartNoAxesCombined },
    ] },
  { heading: "Configuration", items: [
      { label: "Features", href: "/platform/features", icon: ToggleRight },
      { label: "Settings", href: "/platform/settings", icon: SlidersHorizontal },
      { label: "Announcements", href: "/platform/announcements", icon: Megaphone },
    ] },
  { heading: "Operations", items: [
      { label: "Support", href: "/platform/support", icon: LifeBuoy },
      { label: "Notifications", href: "/platform/notifications", icon: Bell },
      { label: "Email log", href: "/platform/email-logs", icon: Mail },
      { label: "Jobs", href: "/platform/jobs", icon: Timer },
      { label: "System health", href: "/status", icon: HeartPulse },
    ] },
  { heading: "Security", items: [
      { label: "Operators", href: "/platform/operators", icon: UserCog, anyPermission: ["PLATFORM_AUDIT_VIEW"] },
      { label: "Audit log", href: "/platform/audit-logs", icon: ScrollText, anyPermission: ["PLATFORM_AUDIT_VIEW"] },
    ] },
];

/** Settings alone, because the billing page beneath it belongs to a customer. */
const isAccountPage = (pathname: string) =>
  pathname === "/settings" || ["/docs", "/platform"].some((page) => pathname === page || pathname.startsWith(`${page}/`));

/** A platform organisation has no records of its own, so its members only get what belongs to their account. */
export function workspaceNavItems(permissions: readonly string[], hasSubscription: boolean): NavItem[] {
  const visible = visibleNavItems(permissions);
  return hasSubscription ? visible : visible.filter(({ href }) => isAccountPage(href));
}

/** An operator of a platform organisation has no workspace, however they reached the page. */
export function leavesWorkspace(pathname: string, permissions: readonly string[], hasSubscription: boolean): boolean {
  return (
    !hasSubscription &&
    permissions.includes("PLATFORM_ADMIN") &&
    !isAccountPage(pathname)
  );
}

export function landingPage(permissions: readonly string[]): string {
  return permissions.includes("PLATFORM_ADMIN") ? "/platform/dashboard" : "/dashboard";
}

export function visibleNavItems(permissions: readonly string[], items = NAV_ITEMS): NavItem[] {
  return items.filter(
    (item) => !item.anyPermission || item.anyPermission.some((p) => permissions.includes(p)),
  );
}
