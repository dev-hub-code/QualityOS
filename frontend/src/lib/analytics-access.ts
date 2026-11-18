/**
 * Which figures someone sees: each follows the view permission of the records behind it, as the server applies it.
 * The server leaves the others out; this only decides which tiles and charts to draw.
 */
export interface FigureAccess {
  inspections: boolean;
  ncrs: boolean;
  capas: boolean;
  suppliers: boolean;
  audits: boolean;
  production: boolean;
  downtime: boolean;
}

export function figureAccess(permissions: readonly string[] = []): FigureAccess {
  const has = (permission: string) => permissions.includes(permission);
  return {
    inspections: has("INSPECTION_VIEW"),
    ncrs: has("NCR_VIEW"),
    capas: has("CAPA_VIEW"),
    suppliers: has("SUPPLIER_VIEW"),
    audits: has("AUDIT_VIEW"),
    production: has("WORK_ORDER_VIEW"),
    downtime: has("WORK_ORDER_VIEW") && has("MANUFACTURING_VIEW"),
  };
}
