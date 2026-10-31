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
  facilityIds: string[];
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
  instruments: UsedInstrument[];
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
  auditId: string | null;
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
  contactPerson: string | null;
  email: string | null;
  phone: string | null;
  address: string | null;
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
  shippedOn: string | null;
  carrierReference: string | null;
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
    expectedQuantity: string;
  }[];
  reversalReason: string | null;
}

export interface SearchHit {
  batchNumber: string;
  serialNumber: string | null;
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
  unconfirmedCustomers: string[];
  recall: RecallLine[];
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

export interface RecallLine {
  dispatchId: string;
  dispatchNumber: string;
  customerId: string;
  customerName: string;
  dispatchedOn: string;
  shippedOn: string | null;
  quantity: string;
  returnedQuantity: string;
  outstandingQuantity: string;
}

export interface SerialSearchResult {
  serialNumber: string;
  productCode: string;
  productName: string;
  batchNumber: string;
  facilityName: string;
  manufacturedAt: string | null;
  inspectionStatus: string | null;
  qualityStatus: string;
  dispatchStatus: string;
  customerName: string | null;
  dispatchNumber: string | null;
  dispatchedOn: string | null;
}

export interface SerialTrace {
  serialNumber: string;
  batchNumber: string;
  productCode: string;
  productName: string;
  batchStatus: string;
  manufacturedAt: string | null;
  inspections: {
    id: string;
    inspectionNumber: string;
    inspectionType: string;
    status: string;
    finalResult: string | null;
    disposition: string | null;
  }[];
  deliveries: {
    dispatchId: string;
    dispatchNumber: string;
    customerName: string;
    dispatchedOn: string;
    shippedOn: string | null;
    returned: boolean;
  }[];
}

export interface RecallSummary {
  id: string;
  recallNumber: string;
  recallDate: string;
  reason: string;
  status: string;
  batchNumbers: string[];
}

export interface ClosureReview {
  customers: number;
  contacted: number;
  acknowledged: number;
  exceptions: number;
  unresolvedCustomers: string[];
  dispatchedQuantity: string;
  returnedQuantity: string;
  outstandingQuantity: string;
  capaStatus: string | null;
  blockers: string[];
}

export interface RecallDetail {
  id: string;
  recallNumber: string;
  recallDate: string;
  reason: string;
  instructions: string;
  capaId: string | null;
  status: "OPEN" | "PENDING_CLOSURE" | "CLOSED";
  batchNumbers: string[];
  closureNote: string | null;
  closureRequestedBy: string | null;
  closureRequestedAt: string | null;
  closedBy: string | null;
  closedAt: string | null;
  review: ClosureReview;
}

export interface RecallDashboard {
  totalCustomers: number;
  pending: number;
  contactAttempts: number;
  contacted: number;
  acknowledged: number;
  unreachable: number;
  returnsInProgress: number;
  resolved: number;
  outstandingFollowUps: number;
  dispatchedQuantity: string;
  returnedQuantity: string;
  outstandingQuantity: string;
}

export interface RecallContact {
  id: string;
  customerId: string;
  customerCode: string;
  customerName: string;
  status: string;
  contactPerson: string | null;
  contactDetails: string | null;
  assignedTo: string | null;
  assignedName: string | null;
  followUpOn: string | null;
  notes: string | null;
  exceptionReason: string | null;
  attempts: number;
  lastAttemptAt: string | null;
  acknowledgedOn: string | null;
  quantity: string;
  returnedQuantity: string;
  outstandingQuantity: string;
}

export interface RecallHistoryEntry {
  id: string;
  kind: "ATTEMPT" | "ACKNOWLEDGEMENT";
  customerName: string;
  method: string | null;
  contactPerson: string | null;
  at: string;
  on: string;
  outcome: string | null;
  customerResponse: string | null;
  notes: string | null;
  recordedBy: string | null;
  kindLabel: string;
  outcomeLabel: string;
}

export interface RecallReport {
  organizationName: string;
  organizationLegalName: string | null;
  organizationAddress: string | null;
  organizationGstin: string | null;
  generatedLabel: string;
  recall: RecallDetail;
  products: { code: string; name: string; batchNumber: string; status: string }[];
  customers: {
    code: string;
    name: string;
    contactPerson: string | null;
    email: string | null;
    phone: string | null;
    address: string | null;
    status: string;
    attempts: number;
    acknowledgedOn: string | null;
    exceptionReason: string | null;
    quantity: string;
    returnedQuantity: string;
    outstandingQuantity: string;
  }[];
  dispatches: RecallLine[];
  serialNumbers: string[];
  inspections: {
    id: string;
    inspectionNumber: string;
    inspectionType: string;
    batchNumber: string;
    status: string;
    finalResult: string | null;
    disposition: string | null;
  }[];
  ncrs: { id: string; ncrNumber: string; title: string; severity: string; status: string }[];
  history: RecallHistoryEntry[];
}

export interface UsedInstrument {
  id: string;
  instrumentCode: string;
  name: string;
  nextDueOn: string;
}

export interface Instrument {
  id: string;
  instrumentCode: string;
  name: string;
  category: string;
  manufacturer: string | null;
  model: string | null;
  serialNumber: string | null;
  facilityId: string;
  location: string | null;
  custodianId: string | null;
  custodianName: string | null;
  frequencyDays: number;
  lastCalibratedOn: string | null;
  nextDueOn: string;
  status: "ACTIVE" | "OUT_OF_SERVICE" | "RETIRED";
  statusReason: string | null;
  dueState: "OK" | "DUE_SOON" | "OVERDUE";
}

export interface CalibrationRecord {
  id: string;
  calibratedOn: string;
  result: "PASS" | "ADJUSTED" | "FAIL";
  performedBy: string;
  certificateNumber: string | null;
  notes: string | null;
  nextDueOn: string;
  recordedAt: string;
}

export interface InstrumentUse {
  inspectionId: string;
  inspectionNumber: string;
  usedOn: string;
  suspect: boolean;
}

export interface ChecklistSummary {
  id: string;
  code: string;
  name: string;
}

export interface Checklist extends ChecklistSummary {
  items: { position: number; question: string; clauseReference: string | null }[];
}

export interface AuditSummary {
  id: string;
  auditNumber: string;
  auditType: string;
  title: string;
  status: string;
  plannedOn: string;
  leadAuditorName: string | null;
  overdue: boolean;
}

export interface AuditItem {
  id: string;
  position: number;
  question: string;
  clauseReference: string | null;
  result: "CONFORMING" | "NONCONFORMING" | "NOT_APPLICABLE" | null;
  note: string | null;
}

export interface AuditFinding {
  id: string;
  number: number;
  grade: string;
  statement: string;
  evidence: string;
  clauseReference: string | null;
  itemPosition: number | null;
  ncr: {
    id: string;
    ncrNumber: string;
    status: string;
    finished: boolean;
    capas: { id: string; capaNumber: string; title: string; status: string }[];
  } | null;
}

export interface Audit {
  id: string;
  auditNumber: string;
  auditType: string;
  title: string;
  scope: string;
  facilityId: string;
  facilityName: string;
  supplierName: string | null;
  productName: string | null;
  checklistName: string | null;
  plannedOn: string;
  leadAuditorId: string;
  leadAuditorName: string | null;
  status: "PLANNED" | "IN_PROGRESS" | "COMPLETED" | "PENDING_CLOSURE" | "CLOSED" | "CANCELLED";
  summary: string | null;
  closureRequestedBy: string | null;
  closureRequestedByName: string | null;
  closureNote: string | null;
  closedByName: string | null;
  closedAt: string | null;
  cancelReason: string | null;
  items: AuditItem[];
  findings: AuditFinding[];
  review: {
    items: number;
    answered: number;
    conforming: number;
    nonconforming: number;
    notApplicable: number;
    blockers: string[];
  };
}

export interface AuditReport {
  organizationName: string;
  organizationLegalName: string | null;
  organizationAddress: string | null;
  generatedLabel: string;
  audit: Audit;
}

export interface Kpis {
  totalInspections: number;
  passedInspections: number;
  failedInspections: number;
  inspectionPassRate: number | null;
  openNcrs: number;
  closedNcrs: number;
  openCapas: number;
  overdueCapas: number;
  supplierRejectionRate: number | null;
  materialRejectionRate: number | null;
  defectRate: number | null;
  firstPassYield: number | null;
  turnaroundHours: number | null;
}

export interface QualityShare {
  name: string;
  inspections: number;
  passed: number;
  passRate: number | null;
}

export interface SupplierScore {
  name: string;
  lots: number;
  rejectedLots: number;
  rejectionRate: number | null;
  ncrs: number;
  openNcrs: number;
}

export interface Overview {
  from: string;
  to: string;
  kpis: Kpis;
  inspectionTrend: { month: string; passed: number; failed: number; rejectedLots: number }[];
  suppliers: SupplierScore[];
  defects: { category: string; ncrs: number; share: number; cumulativeShare: number }[];
  products: QualityShare[];
  facilities: QualityShare[];
  ncrAgeing: { label: string; ncrs: number }[];
  capaTrend: { month: string; opened: number; closed: number }[];
  pending: { inspectionsAwaitingApproval: number; ncrsAwaitingClosure: number; auditsAwaitingClosure: number };
  recent: { kind: string; reference: string; link: string; status: string; on: string }[];
}

export interface ReportTable {
  title: string;
  organizationName: string;
  facilityLabel: string;
  periodLabel: string;
  generatedLabel: string;
  note: string;
  columns: { label: string; weight: number; numeric: boolean }[];
  rows: (string | null)[][];
  truncated: boolean;
}

export type BillingCycle = "MONTHLY" | "ANNUAL";

export interface Plan {
  id: string;
  code: string;
  name: string;
  description: string;
  monthlyPrice: number | null;
  annualPrice: number | null;
  taxPercent: number;
  maxUsers: number | null;
  maxFacilities: number | null;
  features: string[];
  trialDays: number;
  graceDays: number;
  active: boolean;
  version: number;
  currency: string;
  publicVisible: boolean;
  archivedAt: string | null;
  displayOrder: number;
  maxStorageMb: number | null;
  maxMonthlyInspections: number | null;
  maxAttachments: number | null;
  featureValues: Record<string, unknown>;
}

export interface PlanVersion {
  version: number;
  snapshot: Record<string, unknown>;
  reason: string | null;
  changedBy: string | null;
  changedAt: string;
}

export interface PlatformMonth {
  month: string;
  mrr: number;
  paying: number;
  registered: number;
  converted: number;
  collected: number;
  cancelled: number;
}

export interface PlatformDashboard {
  from: string;
  to: string;
  organisations: number;
  active: number;
  trials: number;
  grace: number;
  readOnly: number;
  expired: number;
  mrr: number;
  arr: number;
  outstanding: number;
  outstandingInvoices: number;
  collected: number;
  collectionRate: number | null;
  churnRate: number | null;
  plans: { name: string; count: number }[];
  months: PlatformMonth[];
}

export interface OrganisationDetail {
  tenantId: string;
  name: string;
  legalName: string | null;
  gstin: string | null;
  contactEmail: string | null;
  countryCode: string;
  timezone: string;
  registeredAt: string;
  suspendedAt: string | null;
  suspensionReason: string | null;
  subscription: Subscription;
  usage: { inspections: number; ncrs: number; capas: number; attachments: number; attachmentBytes: number };
  invoices: Invoice[];
  payments: Payment[];
  activity: { action: string; entityType: string; occurredAt: string }[];
}

export interface Subscription {
  planCode: string;
  planName: string;
  state: "TRIAL" | "ACTIVE" | "GRACE" | "READ_ONLY";
  billingCycle: BillingCycle | null;
  endsAt: string;
  readOnlyFrom: string;
  cancelledAt: string | null;
  users: number;
  maxUsers: number | null;
  facilities: number;
  maxFacilities: number | null;
  features: string[];
  storageBytes: number;
  maxStorageMb: number | null;
  inspectionsThisMonth: number;
  maxMonthlyInspections: number | null;
  attachments: number;
  maxAttachments: number | null;
}

export interface Invoice {
  id: string;
  invoiceNumber: string;
  planName: string;
  billingCycle: BillingCycle;
  amount: number;
  taxPercent: number;
  taxAmount: number;
  total: number;
  status: "ISSUED" | "PAID" | "VOID";
  issuedOn: string;
  dueOn: string;
  paidAt: string | null;
  taxTreatment: "INTRA_STATE" | "INTER_STATE" | "UNDETERMINED" | null;
  placeOfSupply: string | null;
  cgstAmount: number | null;
  sgstAmount: number | null;
  igstAmount: number | null;
  sacCode: string | null;
  sellerGstin: string | null;
  buyerName: string;
  buyerGstin: string | null;
  periodStartsAt: string | null;
  periodEndsAt: string | null;
}

export interface Payment {
  id: string;
  invoiceId: string;
  provider: string;
  providerReference: string;
  amount: number;
  status: "SUCCEEDED" | "FAILED";
  failureReason: string | null;
  occurredAt: string;
  method: string | null;
  paidOn: string | null;
}

export interface TenantSubscription {
  tenantId: string;
  organizationName: string;
  subscription: Subscription;
  openInvoices: Invoice[];
  gstin: string | null;
  contactEmail: string | null;
  registeredAt: string;
  subscriptionId: string;
  suspendedAt: string | null;
  nextDue: string | null;
}

export interface OrganisationList {
  total: number;
  items: TenantSubscription[];
}

export interface PlatformInvoice {
  id: string;
  tenantId: string;
  organisation: string;
  invoiceNumber: string;
  planName: string;
  billingCycle: BillingCycle;
  periodStartsAt: string | null;
  periodEndsAt: string | null;
  amount: number;
  taxAmount: number;
  total: number;
  paid: number;
  outstanding: number;
  status: Invoice["status"];
  issuedOn: string;
  dueOn: string;
}

export interface InvoiceList {
  total: number;
  items: PlatformInvoice[];
}

export interface PlatformPayment {
  id: string;
  tenantId: string;
  organisation: string;
  invoiceId: string;
  invoiceNumber: string;
  provider: string;
  reference: string;
  method: string | null;
  amount: number;
  status: Payment["status"];
  failureReason: string | null;
  paidOn: string | null;
  notes: string | null;
  recordedBy: string | null;
  recordedAt: string;
}

export interface PaymentList {
  total: number;
  items: PlatformPayment[];
}

export interface TrialSummary {
  endingIn3Days: number;
  endingIn7Days: number;
  convertedThisMonth: number;
  conversionRate: number | null;
}

export interface Feature {
  code: string;
  name: string;
  description: string;
  valueType: "BOOLEAN" | "NUMBER" | "TEXT" | "STRUCTURED";
  active: boolean;
  defaultEnabled: boolean;
  enforced: boolean;
  organisations: number;
  version: number;
}

export interface FeatureOrganisationList {
  total: number;
  items: { tenantId: string; name: string; planName: string }[];
}

export interface UsageRow {
  tenantId: string;
  name: string;
  planName: string;
  features: string[];
  users: number;
  maxUsers: number | null;
  facilities: number;
  maxFacilities: number | null;
  storageBytes: number;
  maxStorageMb: number | null;
  inspections: number;
  maxMonthlyInspections: number | null;
  attachments: number;
  maxAttachments: number | null;
  level: "OK" | "NEAR" | "REACHED";
  upgradeTo: string | null;
}

export interface UsageList {
  total: number;
  items: UsageRow[];
}

export interface UsageHistory {
  takenOn: string;
  users: number;
  facilities: number;
  storageBytes: number;
  inspections: number;
  attachments: number;
}

export interface PlatformAnalytics {
  from: string;
  to: string;
  mrr: number;
  arr: number;
  openingMrr: number;
  openingCustomers: number;
  closingCustomers: number;
  newCustomers: number;
  registered: number;
  converted: number;
  trialConversion: number | null;
  lostCustomers: number;
  customerChurn: number | null;
  revenueLost: number;
  revenueChurn: number | null;
  averageRevenue: number | null;
  revenueByPlan: { name: string; mrr: number }[];
  plans: { name: string; count: number }[];
  outstanding: number;
  collected: number;
  collectionRate: number | null;
  months: PlatformMonth[];
}

export interface PlatformOperator {
  userId: string;
  email: string;
  fullName: string;
  role: string;
  addedAt: string;
  deactivatedAt: string | null;
  addedBy: string | null;
  lastActivity: string | null;
}

export interface PlatformAuditEntry {
  id: string;
  occurredAt: string;
  actor: string | null;
  action: string;
  entityType: string;
  entityId: string | null;
  organisation: string | null;
  before: Record<string, unknown> | null;
  after: Record<string, unknown> | null;
  reason: string | null;
  correlationId: string | null;
  ipAddress: string | null;
}

export interface PlatformAuditList {
  total: number;
  items: PlatformAuditEntry[];
}

export interface PlatformEmail {
  id: string;
  tenantId: string | null;
  organisation: string | null;
  notificationId: string | null;
  recipient: string;
  template: string;
  status: string;
  attempts: number;
  lastAttempt: string;
  errorType: string | null;
  retryable: boolean;
}

export interface PlatformEmailList {
  total: number;
  items: PlatformEmail[];
}

export interface PlatformNotice {
  id: string;
  sentAt: string;
  type: string;
  title: string;
  organisation: string;
  tenantId: string;
  recipient: string | null;
  emailStatus: string;
}

export interface PlatformNoticeList {
  total: number;
  items: PlatformNotice[];
}

export interface PlatformJob {
  name: string;
  intervalSeconds: number;
  lastStartedAt: string | null;
  lastStatus: string | null;
  lastDurationMs: number | null;
  failuresToday: number;
  stale: boolean;
}

export interface PlatformJobRun {
  startedAt: string;
  durationMs: number;
  tenantsProcessed: number;
  recordsProcessed: number;
  notificationsGenerated: number;
  failureCount: number;
  status: string;
}
