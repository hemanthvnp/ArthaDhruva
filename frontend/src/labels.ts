/** What the models call their inputs, in the words a credit officer uses. */
const FEATURE: Record<string, string> = {
  credit_score: 'Credit score',
  original_dti: 'Debt-to-income',
  original_upb: 'Loan amount',
  original_cltv: 'Combined loan-to-value',
  original_ltv: 'Loan-to-value',
  original_interest_rate: 'Note rate',
  rate_spread: 'Rate spread to market',
  original_loan_term: 'Loan term',
  number_of_borrowers: 'Number of borrowers',
  number_of_units: 'Number of units',
  mi_percent: 'Mortgage insurance',
  occupancy_status: 'Occupancy',
  property_type: 'Property type',
  loan_purpose: 'Loan purpose',
  channel: 'Origination channel',
  first_time_homebuyer_flag: 'First-time homebuyer',
  property_state: 'Property state',
  calibrated_pd: 'The score itself',
  loan_age: 'Loan age',
  regime_stressed: 'Macro regime',
  rate_incentive: 'Rate incentive',
  unemployment: 'Unemployment rate',
  unemployment_change_12m: '12-month change in unemployment',
  hpi_change_12m: '12-month change in house prices',
  mtm_ltv: 'Mark-to-market LTV',
};

export function featureLabel(name: string): string {
  return FEATURE[name] ?? name.replace(/_/g, ' ');
}
