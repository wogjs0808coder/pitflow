export type FinanceWork = {
  id: string;
  vehicle_label: string;
  plate_number: string;
  mechanic_name: string | null;
  completed_at: string;
  invoice_id: string | null;
  invoice_issued: boolean;
  revenue: number;
  paid_amount: number;
  outstanding_amount: number;
  parts_cost_known: number;
  unknown_parts_quantity: number;
  has_unknown_parts_cost: boolean;
  labor_minutes_snapshot: number | null;
  labor_hourly_cost_snapshot: number | null;
  labor_cost: number | null;
  has_unknown_labor_cost: boolean;
  has_unknown_cost: boolean;
  total_cost: number | null;
  contribution_margin: number | null;
};

export type FinanceSummary = {
  from: string;
  to: string;
  work_order_count: number;
  revenue: number;
  paid_amount: number;
  outstanding_amount: number;
  parts_cost_known: number;
  labor_cost_known: number;
  has_unknown_cost: boolean;
  total_cost: number | null;
  contribution_margin: number | null;
};
