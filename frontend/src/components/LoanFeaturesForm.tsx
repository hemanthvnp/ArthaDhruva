import type { LoanFeatures } from '../api/types';

// Real category codes the PD model was trained on (backend/risk-engine/.../category_mappings.json),
// not guessed -- an unlisted value would map to the model's "unseen category" sentinel.
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

export const DEFAULT_LOAN: LoanFeatures = {
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
  occupancyStatus: 'P',
  propertyType: 'SF',
  loanPurpose: 'P',
  channel: 'R',
  firstTimeHomebuyerFlag: 'N',
  propertyState: 'CA',
};

interface Props {
  value: LoanFeatures;
  onChange: (next: LoanFeatures) => void;
  showLoanId?: boolean;
}

export default function LoanFeaturesForm({ value, onChange, showLoanId }: Props) {
  const set = <K extends keyof LoanFeatures>(key: K, v: LoanFeatures[K]) =>
    onChange({ ...value, [key]: v });

  const numberField = (
    key: keyof LoanFeatures,
    label: string,
    step = 1,
  ) => (
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
    key: keyof LoanFeatures,
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

  return (
    <div className="field-grid">
      {showLoanId && (
        <div className="field">
          <label htmlFor="loanId">Loan ID (optional)</label>
          <input
            id="loanId"
            type="text"
            value={value.loanId ?? ''}
            onChange={(e) => set('loanId', e.target.value || undefined)}
            placeholder="for caching + later lookup"
          />
        </div>
      )}
      {numberField('creditScore', 'Credit score (300-850)')}
      {numberField('originalDti', 'DTI (%)', 0.1)}
      {numberField('originalUpb', 'Original UPB ($)', 1000)}
      {numberField('originalCltv', 'CLTV (%)', 0.1)}
      {numberField('originalLtv', 'LTV (%)', 0.1)}
      {numberField('originalInterestRate', 'Interest rate (%)', 0.01)}
      {numberField('originalLoanTerm', 'Loan term (months)')}
      {numberField('numberOfBorrowers', 'Number of borrowers')}
      {numberField('numberOfUnits', 'Number of units')}
      {numberField('miPercent', 'MI percent (%)', 0.1)}
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
    </div>
  );
}
