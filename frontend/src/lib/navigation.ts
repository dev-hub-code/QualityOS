import {
  Boxes,
  Building2,
  ClipboardCheck,
  ClipboardList,
  Building,
  ChartColumn,
  CreditCard,
  FileChartColumn,
  Gauge,
  ListTodo,
  ScanSearch,
  ShieldEllipsis,
  SearchCheck,
  Siren,
  ClipboardX,
  FileCog,
  FilePen,
  Factory,
  GitBranch,
  LayoutDashboard,
  ListChecks,
  Package,
  PackageCheck,
  Send,
  BookOpen,
  Settings,
  ShieldCheck,
  Tags,
  Truck,
  Undo2,
  Users,
  Workflow,
  type LucideIcon,
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
  { heading: "Customers", items: [{ label: "Organisations", href: "/platform/organisations", icon: Building }] },
  { heading: "Billing", items: [{ label: "Plans", href: "/platform/plans", icon: Tags }] },
];

export function visibleNavItems(permissions: readonly string[], items = NAV_ITEMS): NavItem[] {
  return items.filter(
    (item) => !item.anyPermission || item.anyPermission.some((p) => permissions.includes(p)),
  );
}
