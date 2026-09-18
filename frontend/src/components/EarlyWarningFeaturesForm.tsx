import type { EarlyWarningFeatures } from '../api/types';

// Same real category codes as LoanFeaturesForm (backend/risk-engine/.../early_warning_category_mappings.json).
const OCCUPANCY_STATUS = [
  { value: 'P', label: 'P - Primary residence' },
  { value: 'S', label: 'S - Second home' },
  { value: 'I', label: 'I - Investment' },
];
const PROPERTY_TYPE = [
  { value: 'SF', label: 'SF - Single family' },
  { value: 'PU', label: 'PU - PUD' },
  { value: 'CO', label: 'CO - Condo' },
  { value: 'MH', label: 'MH - Manufactured housing' },
  { value: 'CP', label: 'CP - Co-op' },
];
const LOAN_PURPOSE = [
  { value: 'P', label: 'P - Purchase' },
  { value: 'C', label: 'C - Cash-out refi' },
  { value: 'N', label: 'N - No cash-out refi' },
];
const CHANNEL = [
  { value: 'R', label: 'R - Retail' },
  { value: 'B', label: 'B - Broker' },
  { value: 'C', label: 'C - Correspondent' },
];
const FIRST_TIME_HOMEBUYER = [
  { value: 'N', label: 'N - No' },
  { value: 'Y', label: 'Y - Yes' },
];
const PROPERTY_STATE = [
  'AK', 'AL', 'AR', 'AZ', 'CA', 'CO', 'CT', 'DC', 'DE', 'FL', 'GA', 'GU', 'HI', 'IA', 'ID',
  'IL', 'IN', 'KS', 'KY', 'LA', 'MA', 'MD', 'ME', 'MI', 'MN', 'MO', 'MS', 'MT', 'NC', 'ND',
  'NE', 'NH', 'NJ', 'NM', 'NV', 'NY', 'OH', 'OK', 'OR', 'PA', 'PR', 'RI', 'SC', 'SD', 'TN',
  'TX', 'UT', 'VA', 'VI', 'VT', 'WA', 'WI', 'WV', 'WY',
];
// Real values found in hmm_regime_lookup.parquet -- "unknown" covers months the HMM has no
// regime assigned for (e.g. outside its fitted date range), not a fourth macro regime.
const HMM_REGIME = [
  { value: 'calm', label: 'Calm' },
  { value: 'stressed', label: 'Stressed' },
  { value: 'unknown', label: 'Unknown' },
];

export const DEFAULT_EARLY_WARNING_LOAN: EarlyWarningFeatures = {
  creditScore: 720,
  originalDti: 35,
  originalUpb: 250000,
  originalCltv: 80,
  originalLtv: 80,
  originalInterestRate: 6.5,
  originalLoanTerm: 360,
  numberOfBorrowers: 2,
  numberOfUnits: 1,
  miPercent: 0,
  loanAge: 18,
  eltv: 72,
  currentInterestRate: 6.5,
  upbPaydownRatio: 0.04,
  rateLockSeverity: 0,
  eltvChange3m: -0.5,
  upbPaydownChange3m: 0.01,
  rateLockSeverityChange3m: 0,
  eltvChange6m: -1.2,
  upbPaydownChange6m: 0.02,
  rateLockSeverityChange6m: 0,
  occupancyStatus: 'P',
  propertyType: 'SF',
  loanPurpose: 'P',
  channel: 'R',
  firstTimeHomebuyerFlag: 'N',
  propertyState: 'CA',
  hmmRegime: 'calm',
  priorAssistance: false,
  priorModification: false,
  priorDisaster: false,
  upbStalled: false,
};

interface Props {
  value: EarlyWarningFeatures;
  onChange: (next: EarlyWarningFeatures) => void;
}

export default function EarlyWarningFeaturesForm({ value, onChange }: Props) {
  const set = <K extends keyof EarlyWarningFeatures>(key: K, v: EarlyWarningFeatures[K]) =>
    onChange({ ...value, [key]: v });

  const numberField = (key: keyof EarlyWarningFeatures, label: string, step = 1) => (
    <div className="field">
      <label htmlFor={key}>{label}</label>
      <input
        id={key}
        type="number"
        step={step}
        value={value[key] as number}
        onChange={(e) => set(key, Number(e.target.value) as never)}
      />
    </div>
  );

  const selectField = (
    key: keyof EarlyWarningFeatures,
    label: string,
    options: { value: string; label: string }[],
  ) => (
    <div className="field">
      <label htmlFor={key}>{label}</label>
      <select id={key} value={value[key] as string} onChange={(e) => set(key, e.target.value as never)}>
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </div>
  );

  const checkboxField = (key: keyof EarlyWarningFeatures, label: string) => (
    <div className="field" style={{ flexDirection: 'row', alignItems: 'center', gap: '0.5rem' }}>
      <input
        id={key}
        type="checkbox"
        checked={value[key] as boolean}
        onChange={(e) => set(key, e.target.checked as never)}
      />
      <label htmlFor={key} style={{ margin: 0 }}>
        {label}
      </label>
    </div>
  );

  return (
    <div className="field-grid">
      {numberField('creditScore', 'Credit score (300-850)')}
      {numberField('originalDti', 'Original DTI (%)', 0.1)}
      {numberField('originalUpb', 'Original UPB ($)', 1000)}
      {numberField('originalCltv', 'Original CLTV (%)', 0.1)}
      {numberField('originalLtv', 'Original LTV (%)', 0.1)}
      {numberField('originalInterestRate', 'Original interest rate (%)', 0.01)}
      {numberField('originalLoanTerm', 'Loan term (months)')}
      {numberField('numberOfBorrowers', 'Number of borrowers')}
      {numberField('numberOfUnits', 'Number of units')}
      {numberField('miPercent', 'MI percent (%)', 0.1)}
      {numberField('loanAge', 'Loan age (months)')}
      {numberField('eltv', 'Estimated LTV, current (%)', 0.1)}
      {numberField('currentInterestRate', 'Current interest rate (%)', 0.01)}
      {numberField('upbPaydownRatio', 'UPB paydown ratio', 0.01)}
      {numberField('rateLockSeverity', 'Rate-lock severity', 0.01)}
      {numberField('eltvChange3m', 'eLTV change, 3mo', 0.1)}
      {numberField('upbPaydownChange3m', 'UPB paydown change, 3mo', 0.01)}
      {numberField('rateLockSeverityChange3m', 'Rate-lock severity change, 3mo', 0.01)}
      {numberField('eltvChange6m', 'eLTV change, 6mo', 0.1)}
      {numberField('upbPaydownChange6m', 'UPB paydown change, 6mo', 0.01)}
      {numberField('rateLockSeverityChange6m', 'Rate-lock severity change, 6mo', 0.01)}
      {selectField('occupancyStatus', 'Occupancy status', OCCUPANCY_STATUS)}
      {selectField('propertyType', 'Property type', PROPERTY_TYPE)}
      {selectField('loanPurpose', 'Loan purpose', LOAN_PURPOSE)}
      {selectField('channel', 'Channel', CHANNEL)}
      {selectField('firstTimeHomebuyerFlag', 'First-time homebuyer', FIRST_TIME_HOMEBUYER)}
      {selectField(
        'propertyState',
        'Property state',
        PROPERTY_STATE.map((s) => ({ value: s, label: s })),
      )}
      {selectField('hmmRegime', 'Macro regime (as of snapshot month)', HMM_REGIME)}
      {checkboxField('priorAssistance', 'Prior forbearance/assistance')}
      {checkboxField('priorModification', 'Prior modification')}
      {checkboxField('priorDisaster', 'Prior disaster flag')}
      {checkboxField('upbStalled', 'UPB paydown stalled')}
    </div>
  );
}
