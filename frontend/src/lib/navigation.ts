import {
  Boxes,
  ClipboardList,
  Factory,
  LayoutDashboard,
  Package,
  PackageCheck,
  Settings,
  ShieldCheck,
  Truck,
  Users,
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
  { label: "Facilities", href: "/facilities", icon: Factory, anyPermission: ["FACILITY_VIEW"] },
  { label: "Users", href: "/users", icon: Users, anyPermission: ["USER_MANAGE"] },
  { label: "Roles", href: "/roles", icon: ShieldCheck, anyPermission: ["ROLE_MANAGE", "USER_MANAGE"] },
  { label: "Settings", href: "/settings", icon: Settings },
];

export function visibleNavItems(permissions: readonly string[], items = NAV_ITEMS): NavItem[] {
  return items.filter(
    (item) => !item.anyPermission || item.anyPermission.some((p) => permissions.includes(p)),
  );
}
