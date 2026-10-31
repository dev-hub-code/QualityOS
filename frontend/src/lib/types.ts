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

export interface Attachment {
  id: string;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  category: string | null;
  uploadedAt: string;
}

export interface Facility {
  id: string;
  code: string;
  name: string;
  address: string | null;
  city: string | null;
  state: string | null;
  postalCode: string | null;
  gstin: string | null;
  version: number;
}

export interface FacilityUnit {
  id: string;
  code: string;
  name: string;
}

export interface Shift {
  id: string;
  name: string;
  startsAt: string;
  endsAt: string;
}

export interface FacilityDetail {
  facility: Facility;
  departments: FacilityUnit[];
  productionLines: FacilityUnit[];
  shifts: Shift[];
}

export interface Supplier {
  id: string;
  code: string;
  name: string;
  legalName: string | null;
  gstin: string | null;
  categoryId: string | null;
  email: string | null;
  phone: string | null;
  address: string | null;
  city: string | null;
  state: string | null;
  postalCode: string | null;
  status: string;
  statusNote: string | null;
  version: number;
}

export interface SupplierContact {
  id: string;
  name: string;
  designation: string | null;
  email: string | null;
  phone: string | null;
  primary: boolean;
}

export interface SupplierDetail {
  supplier: Supplier;
  contacts: SupplierContact[];
}

export interface Category {
  id: string;
  name: string;
}

export interface Unit {
  id: string;
  code: string;
  name: string;
  dimension: string;
  system: boolean;
}

export interface Specification {
  id: string;
  name: string;
  nominalValue: string | null;
  minValue: string | null;
  maxValue: string | null;
  textValue: string | null;
  uomId: string | null;
  notes: string | null;
}

export interface Material {
  id: string;
  code: string;
  name: string;
  description: string | null;
  grade: string | null;
  categoryId: string | null;
  uomId: string;
  inspectionRequired: boolean;
  version: number;
}

export interface MaterialDetail {
  material: Material;
  specifications: Specification[];
}

export interface Product {
  id: string;
  code: string;
  name: string;
  description: string | null;
  productType: string;
  categoryId: string | null;
  uomId: string;
  currentRevision: string;
  version: number;
}

export interface ProductRevision {
  id: string;
  revision: string;
  changeNote: string | null;
  effectiveFrom: string;
}

export interface BillOfMaterialLine {
  id: string;
  materialId: string;
  materialCode: string;
  materialName: string;
  quantityPerUnit: string;
  uomId: string;
}

export interface ProductDetail {
  product: Product;
  revisions: ProductRevision[];
  specifications: Specification[];
  materials: BillOfMaterialLine[];
}

export interface PurchaseOrderSummary {
  id: string;
  poNumber: string;
  supplierName: string;
  status: string;
  orderDate: string;
  expectedDate: string | null;
}

export interface PurchaseOrderItem {
  id: string;
  lineNumber: number;
  materialId: string;
  materialCode: string;
  materialName: string;
  uomId: string;
  orderedQuantity: string;
  receivedQuantity: string;
  unitPrice: string | null;
}

export interface PurchaseOrder {
  id: string;
  poNumber: string;
  supplierId: string;
  supplierName: string;
  facilityId: string;
  status: string;
  orderDate: string;
  expectedDate: string | null;
  notes: string | null;
  items: PurchaseOrderItem[];
  version: number;
}

export interface ReceiptSummary {
  id: string;
  grnNumber: string;
  purchaseOrderId: string;
  supplierName: string;
  receivedOn: string;
  status: string;
}

export interface ReceiptItem {
  id: string;
  materialCode: string;
  materialName: string;
  supplierBatch: string;
  internalBatch: string;
  quantity: string;
  inspectionStatus: string;
}

export interface Receipt {
  id: string;
  grnNumber: string;
  purchaseOrderId: string;
  supplierName: string;
  receivedOn: string;
  deliveryNote: string | null;
  notes: string | null;
  status: string;
  voidReason: string | null;
  items: ReceiptItem[];
}
