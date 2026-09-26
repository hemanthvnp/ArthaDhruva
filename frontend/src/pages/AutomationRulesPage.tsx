import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createAutomationRule, deleteAutomationRule, listAutomationRules, setAutomationRuleEnabled } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';
import type { ActionType, ConditionOp, RuleAction, RuleTrigger } from '../api/types';

const FIELDS: Record<RuleTrigger, { field: string; numeric: boolean }[]> = {
  LOAN_SCORED: [{ field: 'calibratedRisk', numeric: true }, { field: 'rawRisk', numeric: true }],
  LOAN_CASE_UPDATED: [
    { field: 'status', numeric: false },
    { field: 'flagged', numeric: false },
    { field: 'assignedTo', numeric: false },
  ],
};
const NUMERIC_OPS: ConditionOp[] = ['GT', 'GTE', 'LT', 'LTE', 'EQ', 'NE'];
const TEXT_OPS: ConditionOp[] = ['EQ', 'NE'];
const ACTIONS: { type: ActionType; needsParam: boolean; label: string }[] = [
  { type: 'FLAG_CASE', needsParam: false, label: 'Flag the case' },
  { type: 'ASSIGN_CASE', needsParam: true, label: 'Assign to user' },
  { type: 'SEND_NOTIFICATION', needsParam: true, label: 'Notify user' },
];

export default function AutomationRulesPage() {
  const queryClient = useQueryClient();
  const rules = useQuery({ queryKey: ['automation-rules'], queryFn: listAutomationRules });
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['automation-rules'] });

  const [name, setName] = useState('');
  const [trigger, setTrigger] = useState<RuleTrigger>('LOAN_SCORED');
  const [field, setField] = useState('calibratedRisk');
  const [op, setOp] = useState<ConditionOp>('GT');
  const [value, setValue] = useState('0.2');
  const [action, setAction] = useState<ActionType>('FLAG_CASE');
  const [param, setParam] = useState('');

  const create = useMutation({
    mutationFn: () => {
      const actions: RuleAction[] = [{ type: action, param: ACTIONS.find((a) => a.type === action)?.needsParam ? param : null }];
      return createAutomationRule({ name, trigger, field, op, value, actions });
    },
    onSuccess: () => { setName(''); refresh(); },
  });
  const toggle = useMutation({ mutationFn: ({ id, on }: { id: number; on: boolean }) => setAutomationRuleEnabled(id, on), onSuccess: refresh });
  const remove = useMutation({ mutationFn: deleteAutomationRule, onSuccess: refresh });

  const numeric = FIELDS[trigger].find((f) => f.field === field)?.numeric ?? false;
  const needsParam = ACTIONS.find((a) => a.type === action)?.needsParam ?? false;

  const onTrigger = (t: RuleTrigger) => {
    setTrigger(t);
    setField(FIELDS[t][0].field);
    setOp(FIELDS[t][0].numeric ? 'GT' : 'EQ');
  };
  const onField = (f: string) => {
    setField(f);
    setOp(FIELDS[trigger].find((x) => x.field === f)?.numeric ? 'GT' : 'EQ');
  };

  const submit = (e: FormEvent) => { e.preventDefault(); create.mutate(); };

  return (
    <div>
      <div className="page-head-row">
        <div>
          <h2>Automation Rules</h2>
          <p className="page-subtitle">
            When something happens and a condition matches, do something automatically. Rules run in order; a failing rule never
            blocks the others.
          </p>
        </div>
      </div>

      <form className="card" onSubmit={submit}>
        <div className="field" style={{ marginBottom: '0.8rem' }}>
          <label htmlFor="r-name">Name</label>
          <input id="r-name" value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        <div className="rule-sentence">
          <span className="word">When</span>
          <select aria-label="trigger" id="r-trig" value={trigger} onChange={(e) => onTrigger(e.target.value as RuleTrigger)}>
            <option value="LOAN_SCORED">a loan is scored</option>
            <option value="LOAN_CASE_UPDATED">a case is updated</option>
          </select>
          <span className="word">and</span>
          <select aria-label="field" id="r-field" value={field} onChange={(e) => onField(e.target.value)}>
            {FIELDS[trigger].map((f) => <option key={f.field}>{f.field}</option>)}
          </select>
          <span className="word">is</span>
          <select aria-label="operator" id="r-op" value={op} onChange={(e) => setOp(e.target.value as ConditionOp)}>
            {(numeric ? NUMERIC_OPS : TEXT_OPS).map((o) => <option key={o}>{o}</option>)}
          </select>
          <input aria-label="value" id="r-val" placeholder="value" value={value} onChange={(e) => setValue(e.target.value)} style={{ width: 130 }} />
          <span className="word">then</span>
          <select aria-label="action" id="r-act" value={action} onChange={(e) => setAction(e.target.value as ActionType)}>
            {ACTIONS.map((a) => <option key={a.type} value={a.type}>{a.label}</option>)}
          </select>
          {needsParam && (
            <input aria-label="username" id="r-param" placeholder="username" value={param} onChange={(e) => setParam(e.target.value)} style={{ width: 150 }} />
          )}
        </div>
        <ErrorBanner error={create.error} />
        <div className="actions"><button type="submit" disabled={create.isPending || !name || !value || (needsParam && !param)}>Add rule</button></div>
      </form>

      <ErrorBanner error={rules.error ?? toggle.error ?? remove.error} />
      <div className="card">
        {rules.data?.length === 0 && <p className="empty">No rules yet. Build one above, for example "When a loan is scored and calibratedRisk is GT 0.2, then flag the case".</p>}
        {rules.data && rules.data.length > 0 && (
          <table>
            <thead><tr><th>#</th><th>Name</th><th>Trigger</th><th>Condition</th><th>Actions</th><th>On</th><th /></tr></thead>
            <tbody>
              {rules.data.map((r) => (
                <tr key={r.id}>
                  <td>{r.position}</td>
                  <td>{r.name}</td>
                  <td>{r.trigger.replaceAll('_', ' ').toLowerCase()}</td>
                  <td><code>{r.field} {r.op} {r.value}</code></td>
                  <td>{(JSON.parse(r.actions) as RuleAction[]).map((a, i) => <span key={i} className="badge badge-accent" style={{ marginRight: 4 }}>{a.param ? `${a.type}(${a.param})` : a.type}</span>)}</td>
                  <td><input type="checkbox" aria-label={`enable ${r.name}`} checked={r.enabled} onChange={(e) => toggle.mutate({ id: r.id, on: e.target.checked })} /></td>
                  <td><button className="danger-outline" onClick={() => remove.mutate(r.id)}>Delete</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
