// Compile-time check that the hand-written API types (types.ts) still describe what the backend sends.
//
// openapi.json is the backend's own description of its API, generated from the Java controllers and records
// and held to them by OpenApiContractTest; openapi.gen.ts is that description as TypeScript (npm run
// api:types). Every field a type here declares must exist on the backend's schema of the same object, so a
// field the backend renamed or dropped fails `tsc` (and so `npm run build`) instead of turning into an
// `undefined` in the browser. Fields the backend sends and the frontend does not use are fine.
//
// The comparison is by field name, not by field type: the generated schema has no nullability (every field is
// optional there) and dates are strings on both sides, so a type-level comparison would mostly report noise.
import type { components } from './openapi.gen';
import type * as T from './types';

type Schema = components['schemas'];

/** `true` when every field of the client's type is a field of the server's schema; otherwise names the extra ones. */
type FieldsExistOnServer<Server, Client> = [Exclude<keyof Client, keyof Server>] extends [never]
  ? true
  : { fieldsTheServerDoesNotSend: Exclude<keyof Client, keyof Server> };

type Expect<Check extends true> = Check;

export type ApiContract = [
  Expect<FieldsExistOnServer<Schema['AccessRequestView'], T.AccessRequestView>>,
  Expect<FieldsExistOnServer<Schema['ApproveAccessRequest'], T.ApproveAccessRequest>>,
  Expect<FieldsExistOnServer<Schema['AttachmentView'], T.AttachmentView>>,
  Expect<FieldsExistOnServer<Schema['BulkItemResult'], T.BulkItemResult>>,
  Expect<FieldsExistOnServer<Schema['CaseEventView'], T.CaseEventView>>,
  Expect<FieldsExistOnServer<Schema['CreateRuleRequest'], T.CreateRuleRequest>>,
  Expect<FieldsExistOnServer<Schema['CreateUserRequest'], T.CreateUserRequest>>,
  Expect<FieldsExistOnServer<Schema['CvarRequest'], T.CvarRequest>>,
  Expect<FieldsExistOnServer<Schema['CvarResult'], T.CvarResult>>,
  Expect<FieldsExistOnServer<Schema['Decision'], T.Decision>>,
  Expect<FieldsExistOnServer<Schema['DriftReport'], T.DriftReport>>,
  Expect<FieldsExistOnServer<Schema['EarlyWarningCatalogEntry'], T.EarlyWarningCatalogEntry>>,
  Expect<FieldsExistOnServer<Schema['EarlyWarningFeatures'], T.EarlyWarningFeatures>>,
  Expect<FieldsExistOnServer<Schema['EarlyWarningResponse'], T.EarlyWarningResponse>>,
  Expect<FieldsExistOnServer<Schema['Ecl'], T.Ecl>>,
  Expect<FieldsExistOnServer<Schema['ExpectedLossResponse'], T.ExpectedLossResponse>>,
  Expect<FieldsExistOnServer<Schema['Extrapolation'], T.Extrapolation>>,
  Expect<FieldsExistOnServer<Schema['FeatureDrift'], T.FeatureDrift>>,
  Expect<FieldsExistOnServer<Schema['LoanCaseSummary'], T.LoanCaseSummary>>,
  Expect<FieldsExistOnServer<Schema['LoanCaseView'], T.LoanCaseView>>,
  Expect<FieldsExistOnServer<Schema['LoanFeatures'], T.LoanFeatures>>,
  Expect<FieldsExistOnServer<Schema['LoanRiskProfile'], T.LoanRiskProfile>>,
  Expect<FieldsExistOnServer<Schema['LoanScoreSummary'], T.LoanScoreSummary>>,
  Expect<FieldsExistOnServer<Schema['MacroDriftReport'], T.MacroDriftReport>>,
  Expect<FieldsExistOnServer<Schema['MonthPoint'], T.MonthPoint>>,
  Expect<FieldsExistOnServer<Schema['MonthlyRecord'], T.MonthlyRecord>>,
  Expect<FieldsExistOnServer<Schema['MyLoanView'], T.MyLoanView>>,
  Expect<FieldsExistOnServer<Schema['NotificationView'], T.NotificationView>>,
  Expect<FieldsExistOnServer<Schema['PortfolioView'], T.PortfolioView>>,
  Expect<FieldsExistOnServer<Schema['RecentNoteView'], T.RecentNoteView>>,
  Expect<FieldsExistOnServer<Schema['RegimeForecast'], T.RegimeForecast>>,
  Expect<FieldsExistOnServer<Schema['Scenario'], T.Scenario>>,
  Expect<FieldsExistOnServer<Schema['ScenarioCurve'], T.ScenarioCurve>>,
  Expect<FieldsExistOnServer<Schema['SegmentNeighbor'], T.SegmentNeighbor>>,
  Expect<FieldsExistOnServer<Schema['TermStructure'], T.TermStructure>>,
  Expect<FieldsExistOnServer<Schema['TermStructureResponse'], T.TermStructureResponse>>,
  Expect<FieldsExistOnServer<Schema['TrajectoryCatalogEntry'], T.TrajectoryCatalogEntry>>,
  Expect<FieldsExistOnServer<Schema['TrajectoryRequest'], T.TrajectoryRequest>>,
  Expect<FieldsExistOnServer<Schema['TrajectoryScoreResponse'], T.TrajectoryScoreResponse>>,
  Expect<FieldsExistOnServer<Schema['UserSummary'], T.UserSummary>>,
  // Named differently on the two sides.
  Expect<FieldsExistOnServer<Schema['Run'], T.PortfolioRun>>,
  Expect<FieldsExistOnServer<Schema['DeliveryView'], T.WebhookDelivery>>,
  Expect<FieldsExistOnServer<Schema['Intervals'], T.Intervals>>,
  Expect<FieldsExistOnServer<Schema['Interval'], T.Interval>>,
  Expect<FieldsExistOnServer<Schema['Overview'], T.RealizedOverview>>,
  Expect<FieldsExistOnServer<Schema['ReportView'], T.RealizedReport>>,
  Expect<FieldsExistOnServer<Schema['DelinquencyHistory'], T.DelinquencyHistory>>,
  Expect<FieldsExistOnServer<Schema['Contact'], T.BorrowerContact>>,
  Expect<FieldsExistOnServer<Schema['ContactSetting'], T.BorrowerContactSetting>>,
  Expect<FieldsExistOnServer<Schema['Answer'], T.BorrowerAssistantAnswer>>,
  Expect<FieldsExistOnServer<Schema['ChatRequest'], T.AssistantChatRequest>>,
  Expect<FieldsExistOnServer<Schema['ChatResponse'], T.AssistantChatResponse>>,
  Expect<FieldsExistOnServer<Schema['SubmitAccessRequest'], T.AccessRequestSubmission>>,
  Expect<FieldsExistOnServer<Schema['StateView'], T.SegmentState>>,
  Expect<FieldsExistOnServer<Schema['LinkView'], T.SegmentLink>>,
  Expect<FieldsExistOnServer<Schema['Region'], T.SegmentRegion>>,
  Expect<FieldsExistOnServer<Schema['Graph'], T.SegmentGraph>>,
];
