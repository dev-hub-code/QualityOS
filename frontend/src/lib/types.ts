export interface OrganizationSummary {
  id: string;
  name: string;
}

export interface Me {
  user: { id: string; email: string; fullName: string; emailVerified: boolean; language: string | null };
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

export interface ParameterSpec {
  code: string;
  name: string;
  description: string | null;
  dataType: "NUMERIC" | "TEXT" | "BOOLEAN" | "DATE";
  uomId: string | null;
  nominalValue: string | null;
  minValue: string | null;
  maxValue: string | null;
  tolerance: string | null;
  expectedText: string | null;
  criticality: "CRITICAL" | "MAJOR" | "MINOR";
  mandatory: boolean;
  perSample: boolean;
  evidenceRequired: boolean;
  inspectionMethod: string | null;
  remarks: string | null;
}

export interface TemplateSummary {
  id: string;
  code: string;
  name: string;
  inspectionType: string;
  materialId: string | null;
  publishedVersion: number | null;
  hasDraft: boolean;
}

export interface TemplateVersion {
  id: string;
  versionNumber: number;
  status: string;
  changeNote: string | null;
  samplingType: "FIXED" | "PERCENTAGE" | "FULL";
  sampleSize: number | null;
  samplePercent: string | null;
  acceptanceNumber: number;
  effectiveFrom: string | null;
  effectiveTo: string | null;
  parameters: ParameterSpec[];
}

export interface TemplateDetail {
  template: TemplateSummary;
  versions: TemplateVersion[];
}

export interface ReceivedBatch {
  itemId: string;
  grnNumber: string;
  receivedOn: string;
  supplierName: string;
  materialId: string;
  materialCode: string;
  materialName: string;
  supplierBatch: string;
  internalBatch: string;
  quantity: string;
}

export interface InspectionSummary {
  id: string;
  inspectionNumber: string;
  inspectionType: string;
  supplierName: string | null;
  materialCode: string;
  materialName: string;
  batchNumber: string;
  status: string;
  evaluatedResult: string | null;
  finalResult: string | null;
  disposition: string | null;
}

export interface InspectionResult {
  parameterCode: string;
  sampleNumber: number;
  valueNumeric: string | null;
  valueText: string | null;
  valueBoolean: boolean | null;
  valueDate: string | null;
  evaluation: string;
  remarks: string | null;
}

export interface InspectionDecision {
  decision: string;
  note: string | null;
  decidedBy: string;
  decidedAt: string;
}

export interface Inspection {
  id: string;
  inspectionNumber: string;
  inspectionType: string;
  supplierName: string | null;
  materialCode: string;
  materialName: string;
  batchNumber: string;
  quantity: string;
  sampleSize: number;
  status: string;
  evaluatedResult: string | null;
  finalResult: string | null;
  disposition: string | null;
  submittedAt: string | null;
  approvedAt: string | null;
  specification: {
    templateCode: string;
    templateName: string;
    versionNumber: number;
    samplingType: string;
    acceptanceNumber: number;
    parameters: ParameterSpec[];
  };
  results: InspectionResult[];
  decisions: InspectionDecision[];
}

export interface Invitation {
  id: string;
  email: string;
  expiresAt: string;
  expired: boolean;
}

export interface InvitationPreview {
  organizationName: string;
  email: string;
  existingAccount: boolean;
}

export interface AppNotification {
  id: string;
  type: string;
  title: string;
  body: string;
  link: string | null;
  createdAt: string;
  read: boolean;
}

export interface NotificationPreference {
  type: string;
  emailEnabled: boolean;
}

export interface Assignee {
  id: string;
  fullName: string;
}

export interface DefectCategory {
  id: string;
  code: string;
  name: string;
}

export interface NcrSummary {
  id: string;
  ncrNumber: string;
  title: string;
  status: string;
  severity: string;
  source: string;
  supplierName: string | null;
  materialName: string | null;
  batchNumber: string | null;
  assigneeName: string | null;
  createdAt: string;
}

export interface StatusChange {
  fromStatus: string | null;
  toStatus: string;
  note: string | null;
  actorName: string;
  at: string;
}

export interface Ncr extends NcrSummary {
  description: string;
  inspectionId: string | null;
  defectCategoryId: string | null;
  defectCategoryName: string | null;
  rejectedQuantity: string | null;
  assignedTo: string | null;
  containmentAction: string | null;
  rootCause: string | null;
  outcomeNote: string | null;
  raisedByName: string;
  verificationRequestedBy: string | null;
  closedAt: string | null;
  capas: { id: string; capaNumber: string; title: string; status: string }[];
  history: StatusChange[];
}

export interface CapaSummary {
  id: string;
  capaNumber: string;
  title: string;
  status: string;
  ownerName: string | null;
  targetDate: string;
  overdue: boolean;
}

export interface CapaAction {
  id: string;
  actionType: "CORRECTIVE" | "PREVENTIVE";
  description: string;
  ownerName: string | null;
  targetDate: string;
  completedAt: string | null;
  completionNote: string | null;
}

export interface Capa extends CapaSummary {
  problemStatement: string;
  ncrId: string | null;
  ncrNumber: string | null;
  rootCause: string | null;
  fiveWhys: string[];
  fishbone: Record<string, string[]>;
  ownerId: string;
  effectivenessRequired: boolean;
  outcomeNote: string | null;
  closedAt: string | null;
  actions: CapaAction[];
  verifications: { effective: boolean; method: string; note: string; verifiedBy: string; at: string }[];
  history: StatusChange[];
}

export interface Customer {
  id: string;
  code: string;
  name: string;
  gstin: string | null;
}

export interface ReleasedLot {
  itemId: string;
  materialCode: string;
  materialName: string;
  internalBatch: string;
  remainingQuantity: string;
}

export interface ProductionBatch {
  id: string;
  batchNumber: string;
  productId: string;
  productCode: string;
  productName: string;
  status: string;
  plannedQuantity: string;
  producedQuantity: string | null;
  availableQuantity: string;
}

export interface BatchDetail {
  batch: ProductionBatch;
  consumptions: {
    id: string;
    sourceBatch: string;
    description: string;
    quantity: string;
    at: string;
    reversalReason: string | null;
  }[];
  serialNumbers: string[];
}

export interface Dispatch {
  id: string;
  dispatchNumber: string;
  customerName: string;
  dispatchedOn: string;
  reference: string | null;
  lines: {
    id: string;
    batchNumber: string;
    productCode: string;
    productName: string;
    quantity: string;
    returnedQuantity: string;
  }[];
  reversalReason: string | null;
}

export interface SearchHit {
  batchNumber: string;
  kind: string;
  description: string;
  matchedOn: string;
}

export interface TraceNode {
  id: string;
  kind: "LOT" | "PRODUCTION_BATCH";
  batchNumber: string;
  code: string;
  name: string;
  quantity: string;
  status: string;
  supplierName: string | null;
  supplierBatch: string | null;
  receiptNumber: string | null;
  purchaseOrderNumber: string | null;
}

export interface TimelineEvent {
  at: string;
  kind: string;
  title: string;
  batchNumber: string | null;
  status: string | null;
  link: string | null;
}

export interface TraceReport {
  subject: TraceNode;
  backward: TraceNode[];
  forward: TraceNode[];
  dispatches: Dispatch[];
  returns: DispatchReturn[];
  affectedCustomers: string[];
  serialNumbers: string[];
  timeline: TimelineEvent[];
}

export interface CorrectionSummary {
  id: string;
  correctionNumber: string;
  targetType: "LOT" | "CONSUMPTION";
  status: string;
  subject: string;
  reason: string;
  requestedByName: string | null;
}

export interface CorrectionDetail extends CorrectionSummary {
  productionBatchId: string | null;
  requestedBy: string;
  additionalApproval: boolean;
  approvalsNeeded: number;
  changes: { field: string; originalValue: string | null; correctedValue: string }[];
  approvals: { approverName: string | null; note: string | null; at: string }[];
  impact: { inspected: boolean; batches: string[]; customers: string[] } | null;
  decisionNote: string | null;
  appliedAt: string | null;
}

export interface DispatchReturn {
  id: string;
  reversalNumber: string;
  dispatchNumber: string;
  customerName: string;
  returnReference: string;
  reversalDate: string;
  reason: string;
  status: string;
  recordedByName: string | null;
  items: {
    id: string;
    batchNumber: string;
    productCode: string;
    quantity: string;
    serialNumbers: string[];
    status: string;
    disposition: string | null;
  }[];
}
