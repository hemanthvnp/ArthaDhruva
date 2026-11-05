import type { DelinquencyHistory, LifetimeRiskInput, TermStructureResponse } from '../api/types';

/** The history form as typed: every field a string, blank meaning "not given". */
export interface DelinquencyDraft {
  monthsObserved: string;
  episodes: string;
  monthsSinceCure: string;
  maxStatus: '' | 'CURRENT' | 'D30' | 'D60';
}

export const EMPTY_DRAFT: DelinquencyDraft = { monthsObserved: '', episodes: '', monthsSinceCure: '', maxStatus: '' };

export type DraftField = keyof DelinquencyDraft | 'daysPastDue';

export interface DelinquencyCheck {
  /** `none`: nothing entered, the request carries no delinquency fields; `incomplete`: the server will use the existing
   * engine and say why; `ready`: the delinquency model will run */
  state: 'none' | 'incomplete' | 'ready';
  errors: Partial<Record<DraftField, string>>;
  /** sent only when `state` is not `none` */
  history?: DelinquencyHistory;
}

const RANK = { CURRENT: 0, D30: 1, D60: 2 } as const;

function whole(text: string): number | null {
  if (text.trim() === '') return null;
  const n = Number(text);
  return Number.isInteger(n) ? n : NaN;
}

/** The status a days-past-due figure puts a loan in: 0 current, 1 30 days, 2 60 days; null at 90 or more. */
export function startState(daysPastDue: number): 0 | 1 | 2 | null {
  if (daysPastDue >= 90) return null;
  return daysPastDue < 30 ? 0 : daysPastDue < 60 ? 1 : 2;
}

/**
 * The same rules the API applies (survival/DelinquencyRouter), so a mistake is shown where it is made and not as a
 * 400 after the request. A blank form means no delinquency fields at all; a partly filled one is sent as typed and the
 * server falls back to the existing engine and says so.
 */
export function checkDelinquency(daysPastDue: string, draft: DelinquencyDraft): DelinquencyCheck {
  const errors: DelinquencyCheck['errors'] = {};
  const anyHistory = draft.monthsObserved !== '' || draft.episodes !== '' || draft.monthsSinceCure !== '' || draft.maxStatus !== '';
  if (!anyHistory) return { state: 'none', errors };

  const observed = whole(draft.monthsObserved);
  const episodes = whole(draft.episodes);
  const cure = whole(draft.monthsSinceCure);
  const dpd = whole(daysPastDue);
  if (observed !== null && (Number.isNaN(observed) || observed < 1 || observed > 12)) errors.monthsObserved = 'Whole months from 1 to 12.';
  if (episodes !== null && (Number.isNaN(episodes) || episodes < 0 || episodes > 12)) errors.episodes = 'A whole number from 0 to 12.';
  if (cure !== null && (Number.isNaN(cure) || cure < 0 || cure > 12)) errors.monthsSinceCure = 'Whole months from 0 to 12.';
  if (dpd !== null && Number.isNaN(dpd)) errors.daysPastDue = 'A whole number of days.';

  const complete = observed !== null && episodes !== null && draft.maxStatus !== '';
  if (!errors.monthsObserved && !errors.episodes && observed !== null && episodes !== null && 2 * episodes - 1 > observed) {
    errors.episodes = `${episodes} spell${episodes === 1 ? '' : 's'} need at least ${2 * episodes - 1} months of history (a current month between spells), but ${observed} observed.`;
  }
  if (!errors.monthsSinceCure && cure !== null && observed !== null && cure > observed) errors.monthsSinceCure = `More months than the ${observed} observed.`;

  if (complete && dpd !== null && !errors.daysPastDue) {
    const start = startState(dpd);
    const worst = RANK[draft.maxStatus as 'CURRENT' | 'D30' | 'D60'];
    if (start === null) {
      errors.daysPastDue = 'A loan 90 or more days past due has defaulted: leave the history blank to project it with the existing engine.';
    } else {
      if (start > 0 && episodes! < 1) errors.episodes ??= 'The current spell counts: at least 1.';
      if (worst < start) errors.maxStatus ??= 'The worst status cannot be better than the current one.';
      if (episodes === 0 && worst > 0) errors.maxStatus ??= 'No spells, so the worst status is current.';
      if (episodes! > 0 && worst === 0) errors.maxStatus ??= 'There were spells, so the worst status is at least 30 days.';
      if (cure !== null && episodes === 0) errors.monthsSinceCure ??= 'Nothing to cure: no spells.';
      if (start === 0 && episodes! > 0 && cure === null) errors.monthsSinceCure ??= 'A current loan that had a spell has cured: months since the cure.';
    }
  }
  if (Object.keys(errors).length > 0) return { state: complete ? 'ready' : 'incomplete', errors };

  const history: DelinquencyHistory = {
    ...(observed !== null && { monthsObserved: observed }),
    ...(episodes !== null && { episodes30Plus: episodes }),
    ...(cure !== null && { monthsSinceCure: cure }),
    ...(draft.maxStatus !== '' && { maxStatus12m: draft.maxStatus }),
  };
  return { state: complete && dpd !== null ? 'ready' : 'incomplete', errors, history };
}

/**
 * The history to compare one loan from each starting status with: the user's, adjusted so it is consistent with that
 * status (a late loan has at least one spell and its worst status is at least today's). Everything else is held.
 */
export function historyFor(state: 0 | 1 | 2, history: DelinquencyHistory): DelinquencyHistory {
  const worst = RANK[(history.maxStatus12m ?? 'CURRENT') as 'CURRENT' | 'D30' | 'D60'];
  const maxStatus12m = (['CURRENT', 'D30', 'D60'] as const)[Math.max(worst, state)];
  return {
    ...history,
    episodes30Plus: Math.max(history.episodes30Plus ?? 0, state > 0 ? 1 : 0),
    maxStatus12m,
  };
}

/** Days past due that represent each starting state in the comparison. */
export const STATE_DAYS = [0, 30, 60] as const;
export const STATE_LABEL = ['Current', '30 days past due', '60 days past due'] as const;

/** The request with the history added only when something was typed: a blank form is the request as it always was. */
export function withDelinquency(input: LifetimeRiskInput, check: DelinquencyCheck): LifetimeRiskInput {
  return check.state === 'none' || !check.history ? input : { ...input, delinquency: check.history };
}

/** What the server will do with what is typed, in words, so a blank or half-filled form is not a surprise. */
export function checkSummary(check: DelinquencyCheck): string {
  if (check.state === 'none') return 'Leave blank to project the loan as the existing model sees it: from its origination attributes and the macro path.';
  if (check.state === 'incomplete') return 'Incomplete: the existing model will run and the result will say why. Give the days past due, months observed, spells and worst status to use the delinquency model.';
  return 'Complete: the projection starts from this status and history.';
}

/** A loan with no history given is compared from a clean one: twelve months, no spells. */
export const CLEAN_HISTORY: DelinquencyHistory = { monthsObserved: 12, episodes30Plus: 0, maxStatus12m: 'CURRENT' };

export interface ComparisonRow {
  state: 0 | 1 | 2;
  label: string;
  response: TermStructureResponse | undefined;
}

/** One row per starting state, in order of state, whatever order the answers arrived in. */
export function comparisonRows(responses: (TermStructureResponse | undefined)[]): ComparisonRow[] {
  return ([0, 1, 2] as const).map((state) => ({ state, label: STATE_LABEL[state], response: responses[state] }));
}
