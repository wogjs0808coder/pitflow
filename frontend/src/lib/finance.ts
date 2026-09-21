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
  automatic_parts_cost_known: number;
  manual_unresolved_parts_cost: number | null;
  parts_cost_known: number;
  parts_cost: number;
  parts_cost_manually_resolved: boolean;
  unknown_parts_quantity: number;
  has_unknown_parts_cost: boolean;
  labor_minutes_snapshot: number | null;
  labor_hourly_cost_snapshot: number | null;
  current_monthly_base_salary: number | null;
  current_monthly_standard_hours: number | null;
  current_derived_hourly_cost: number | null;
  automatic_labor_cost: number | null;
  manual_labor_cost: number | null;
  labor_cost: number | null;
  labor_cost_manually_resolved: boolean;
  has_unknown_labor_cost: boolean;
  has_unknown_cost: boolean;
  total_cost: number | null;
  contribution_margin: number | null;
  cost_resolution_reason: string | null;
  cost_resolution_at: string | null;
  cost_resolution_by: string | null;
};

export type FinanceSummary = {
  from: string;
  to: string;
  work_order_count: number;
  invoiced_work_order_count: number;
  revenue: number;
  paid_amount: number;
  outstanding_amount: number;
  parts_cost_known: number;
  labor_cost_known: number;
  parts_cost: number;
  labor_cost: number;
  has_unknown_cost: boolean;
  total_cost: number | null;
  contribution_margin: number | null;
  collection_rate: number | null;
  average_repair_order: number | null;
  parts_cost_ratio: number | null;
  allocated_labor_cost: number;
  period_payroll_expense: number | null;
  payroll_expense: number | null;
  payroll_unknown: boolean;
  payroll_ratio: number | null;
  target_payroll_ratio: number;
  labor_allocation_variance: number | null;
  contribution_margin_ratio: number | null;
  gross_profit: number | null;
  operating_expenses: number;
  operating_profit: number | null;
  operating_margin: number | null;
  other_income: number;
  interest_expense: number;
  pre_tax_profit: number | null;
  tax_expense: number;
  net_profit: number | null;
  expense_by_category: Record<string, number>;
  completed_labor_hours: number | null;
  standard_available_hours: number;
  labor_utilization_rate: number | null;
  revenue_per_mechanic: number | null;
  mechanics: FinanceMechanic[];
};

export type FinanceMechanic = {
  id: string;
  code: string;
  name: string;
  monthly_base_salary: number | null;
  monthly_standard_hours: number;
  derived_hourly_cost: number | null;
  allocated_minutes: number | null;
  allocated_labor_cost: number;
  period_payroll_expense: number | null;
  standard_available_hours: number;
};

export type FinanceSettings = {
  default_monthly_base_salary: number;
  default_monthly_standard_hours: number;
  target_payroll_ratio: number;
};

export type FinanceEntry = {
  id: string;
  entry_date: string;
  category: string;
  amount: number;
  description: string;
  entry_kind: "ENTRY" | "REVERSAL";
  original_entry_id: string | null;
  created_by_name: string;
  created_at: string;
  reversed: boolean;
  affects_treasury: boolean;
};

export type TreasuryAccountType = "OPERATING" | "DEPOSIT" | "INVESTMENT";

export type TreasuryAccount = {
  balance: number;
  target_ratio: number;
  current_ratio: number;
};

export type TreasuryInventoryItem = {
  part_id: string;
  sku: string;
  name: string;
  unit: string;
  on_hand_quantity: number;
  known_quantity: number;
  known_asset_value: number;
  unknown_quantity: number;
  has_unknown_cost: boolean;
};

export type TreasuryInventory = {
  known_value: number;
  unknown_quantity: number;
  unknown_part_count: number;
  items: TreasuryInventoryItem[];
};

export type TreasurySimulation = {
  annual_deposit_rate: number;
  last_settlement_date: string | null;
  last_deposit_interest: number | null;
  last_investment_return_rate: number | null;
  last_investment_return_amount: number | null;
};

export type TreasurySummary = {
  total_assets: number;
  financial_assets_total: number;
  managed_assets_known_total: number;
  managed_assets_fully_known: boolean;
  inventory: TreasuryInventory;
  receivables: number;
  accounts: Record<TreasuryAccountType, TreasuryAccount>;
  simulation: TreasurySimulation;
  last_updated_at: string;
  can_rebalance: boolean;
};
