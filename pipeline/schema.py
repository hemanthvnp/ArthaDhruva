"""Freddie Mac Single-Family Loan-Level Dataset column layout.

Column order and semantics verified against the standard (current, non-HARP)
32-column origination and 32-column performance layouts, and cross-checked
against sampled rows from historical_data_2025Q3.zip.

Each ColumnSpec's `kind` drives typing:
  - "int", "float", "string": cast directly at the readable stage.
  - "date_yyyymm": kept as an Int32 YYYYMM code at the readable stage (its
    documented raw format); the cleaning stage converts it to a real Date.

`sentinels` lists the raw string values (as they appear in the pipe-delimited
file, before casting) that represent "missing"/"not available" for that
column and must become null during cleaning. Values that merely look like a
sentinel used elsewhere (e.g. "7" in mi_cancellation_indicator = "Not
Applicable", a valid value) are intentionally excluded.
"""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True)
class ColumnSpec:
    name: str
    kind: str  # "int" | "float" | "string" | "date_yyyymm"
    description: str
    sentinels: tuple[str, ...] = field(default_factory=tuple)


ORIGINATION_COLUMNS: list[ColumnSpec] = [
    ColumnSpec("credit_score", "int", "Borrower credit score at origination", ("9999",)),
    ColumnSpec("first_payment_date", "date_yyyymm", "Date of first scheduled payment (YYYYMM)"),
    ColumnSpec("first_time_homebuyer_flag", "string", "Y/N/9 (9 = not available)", ("9",)),
    ColumnSpec("maturity_date", "date_yyyymm", "Scheduled maturity date (YYYYMM)"),
    ColumnSpec("msa", "string", "Metropolitan Statistical Area / Division code", ("", "00000")),
    ColumnSpec("mi_percent", "float", "Mortgage insurance percentage", ("999",)),
    ColumnSpec("number_of_units", "int", "Number of dwelling units", ("99",)),
    ColumnSpec("occupancy_status", "string", "P=primary, S=second home, I=investment", ("9",)),
    ColumnSpec("original_cltv", "float", "Original combined loan-to-value ratio", ("999",)),
    ColumnSpec("original_dti", "float", "Original debt-to-income ratio", ("999",)),
    ColumnSpec("original_upb", "float", "Original unpaid principal balance"),
    ColumnSpec("original_ltv", "float", "Original loan-to-value ratio", ("999",)),
    ColumnSpec("original_interest_rate", "float", "Original interest rate"),
    ColumnSpec("channel", "string", "Origination channel: R/B/C/T", ("9",)),
    ColumnSpec("prepayment_penalty_flag", "string", "Prepayment penalty mortgage flag Y/N"),
    ColumnSpec("amortization_type", "string", "FRM (fixed) or ARM (adjustable)"),
    ColumnSpec("property_state", "string", "Property state (2-letter USPS code)"),
    ColumnSpec("property_type", "string", "CO/PU/MH/SF/CP", ("99",)),
    ColumnSpec("postal_code", "string", "Property postal code (last 2 digits masked)"),
    ColumnSpec("loan_sequence_number", "string", "Unique loan identifier (primary key)"),
    ColumnSpec("loan_purpose", "string", "P=purchase, C=cash-out refi, N=no cash-out refi", ("9",)),
    ColumnSpec("original_loan_term", "int", "Original loan term in months"),
    ColumnSpec("number_of_borrowers", "int", "Number of borrowers on the loan", ("99",)),
    ColumnSpec("seller_name", "string", "Name of the selling institution"),
    ColumnSpec("servicer_name", "string", "Name of the servicing institution"),
    ColumnSpec("super_conforming_flag", "string", "Y if loan exceeds standard conforming limits"),
    ColumnSpec("pre_harp_loan_sequence_number", "string", "Original loan sequence number pre-HARP refinance"),
    ColumnSpec("program_indicator", "string", "Freddie Mac relief-refinance program indicator", ("9",)),
    ColumnSpec("harp_indicator", "string", "Y if originated under HARP"),
    ColumnSpec("property_valuation_method", "int", "1=appraisal, 2=other, 3=GSE-targeted refi, 9=n/a", ("9",)),
    ColumnSpec("interest_only_flag", "string", "Y/N interest-only indicator"),
    ColumnSpec("mi_cancellation_indicator", "string", "Y/N/7 (7=not applicable)/9 (n/a)", ("9",)),
]

PERFORMANCE_COLUMNS: list[ColumnSpec] = [
    ColumnSpec("loan_sequence_number", "string", "Unique loan identifier (foreign key to origination)"),
    ColumnSpec("monthly_reporting_period", "date_yyyymm", "Reporting month (YYYYMM)"),
    ColumnSpec("current_actual_upb", "float", "Current unpaid principal balance"),
    ColumnSpec("current_loan_delinquency_status", "string", "Months delinquent (0, 1, 2, ... or RA/XX)"),
    ColumnSpec("loan_age", "int", "Number of months since origination"),
    ColumnSpec("remaining_months_to_maturity", "int", "Remaining months to legal maturity"),
    ColumnSpec("defect_settlement_date", "date_yyyymm", "Repurchase/defect settlement date (YYYYMM)"),
    ColumnSpec("modification_flag", "string", "Y/N/P loan modification flag"),
    ColumnSpec("zero_balance_code", "string", "Reason UPB went to zero (01=prepaid,03=fcl,09=REO,...)"),
    ColumnSpec("zero_balance_effective_date", "date_yyyymm", "Effective date of zero-balance event (YYYYMM)"),
    ColumnSpec("current_interest_rate", "float", "Current interest rate"),
    ColumnSpec("current_deferred_upb", "float", "Current non-interest-bearing UPB"),
    ColumnSpec("ddlpi", "date_yyyymm", "Due date of last paid installment (YYYYMM)"),
    ColumnSpec("mi_recoveries", "float", "Mortgage insurance recoveries"),
    ColumnSpec("net_sale_proceeds", "string", "Net sale proceeds (numeric or 'U'=unknown)", ("U",)),
    ColumnSpec("non_mi_recoveries", "float", "Non-mortgage-insurance recoveries"),
    ColumnSpec("expenses", "float", "Total expenses"),
    ColumnSpec("legal_costs", "float", "Legal costs"),
    ColumnSpec("maintenance_preservation_costs", "float", "Maintenance and preservation costs"),
    ColumnSpec("taxes_and_insurance", "float", "Taxes and insurance"),
    ColumnSpec("miscellaneous_expenses", "float", "Miscellaneous expenses"),
    ColumnSpec("actual_loss_calculation", "float", "Actual loss calculation"),
    ColumnSpec("modification_cost", "float", "Modification cost"),
    ColumnSpec("step_modification_flag", "string", "Y/N step modification flag"),
    ColumnSpec("deferred_payment_plan", "string", "Y/N deferred payment plan flag"),
    ColumnSpec("eltv", "float", "Estimated loan-to-value", ("999",)),
    ColumnSpec("zero_balance_removal_upb", "float", "UPB at time of zero-balance removal"),
    ColumnSpec("delinquent_accrued_interest", "float", "Delinquent accrued interest"),
    ColumnSpec("delinquency_due_to_disaster", "string", "Y/N delinquency-due-to-disaster flag"),
    ColumnSpec("borrower_assistance_status_code", "string", "F/R/T borrower assistance status code"),
    ColumnSpec("current_month_modification_cost", "float", "Current month modification cost"),
    ColumnSpec("interest_bearing_upb", "float", "Current interest-bearing UPB"),
]


def column_names(columns: list[ColumnSpec]) -> list[str]:
    return [c.name for c in columns]


def date_columns(columns: list[ColumnSpec]) -> list[str]:
    return [c.name for c in columns if c.kind == "date_yyyymm"]
