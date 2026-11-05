package com.arthadhruva.riskengine.survival;

import org.springframework.stereotype.Component;

/**
 * Decides which model a projection runs on, from the loan's current status and history, and validates the history.
 *
 * <p>The delinquency model runs only when the status ({@code daysPastDue}) and the history are both given and the loan
 * is performing or at most 89 days past due. Everything else runs on the existing engine, unchanged, and the response says
 * which model ran and why: a request without the new fields behaves exactly as before.
 */
@Component
public class DelinquencyRouter {

    public static final String NO_DATA = "no delinquency data supplied";
    public static final String CURRENT_NO_HISTORY = "current loan with no history supplied";
    public static final String INCOMPLETE = "history incomplete, delinquency model not used";
    public static final String NO_STATUS = "history supplied without the current status (daysPastDue), delinquency model not used";

    private static final String[] STATUS = {"CURRENT", "D30", "D60"};

    /** An input the delinquency model cannot take, naming the field: answered with 400. */
    public static class InvalidDelinquencyInputException extends RuntimeException {
        private final String field;

        public InvalidDelinquencyInputException(String field, String message) {
            super(message);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    /**
     * @param useDelinquencyModel true when the transition model runs
     * @param reason              why the existing engine ran instead (null when the delinquency model did)
     * @param startState          0 current, 1 30 days past due, 2 60 days past due
     */
    public record Route(boolean useDelinquencyModel, String reason, int startState, DelinquencyModel.History history) {
    }

    private final SurvivalModel survival;
    private final DelinquencyModel model;

    public DelinquencyRouter(SurvivalModel survival, DelinquencyModel model) {
        this.survival = survival;
        this.model = model;
    }

    /** The id and version of the model that answers a route. */
    public String modelUsed(Route route) {
        return route.useDelinquencyModel() ? model.version() : survival.version();
    }

    public Route route(Integer daysPastDue, DelinquencyHistory history) {
        if (history == null) {
            if (daysPastDue == null) {
                return fallback(NO_DATA);
            }
            return fallback(daysPastDue < 30 ? CURRENT_NO_HISTORY : INCOMPLETE);
        }
        if (history.monthsObserved() == null || history.episodes30Plus() == null || history.maxStatus12m() == null) {
            return fallback(INCOMPLETE);
        }
        if (daysPastDue == null) {
            return fallback(NO_STATUS);
        }
        if (daysPastDue >= 90) {
            throw new InvalidDelinquencyInputException("daysPastDue", "daysPastDue is " + daysPastDue + ": a loan 90 or more days "
                    + "past due has defaulted and is not projected forward by the delinquency model; omit the history to use "
                    + "the existing engine, which stages it");
        }
        int start = daysPastDue < 30 ? 0 : daysPastDue < 60 ? 1 : 2;
        int observed = history.monthsObserved();
        int episodes = history.episodes30Plus();
        int worst = indexOf(history.maxStatus12m());
        Integer cure = history.monthsSinceCure();
        if (2 * episodes - 1 > observed) {
            throw new InvalidDelinquencyInputException("history.episodes30Plus", "history.episodes30Plus is " + episodes
                    + " but only " + observed + " month(s) were observed: separate spells need a current month between them, "
                    + "so " + episodes + " spells take at least " + (2 * episodes - 1) + " months");
        }
        if (cure != null && cure > observed) {
            throw new InvalidDelinquencyInputException("history.monthsSinceCure", "history.monthsSinceCure is " + cure
                    + " but only " + observed + " month(s) were observed");
        }
        if (start > 0 && episodes < 1) {
            throw new InvalidDelinquencyInputException("history.episodes30Plus", "history.episodes30Plus is 0 but the loan is "
                    + daysPastDue + " days past due: the current spell is one");
        }
        if (worst < start) {
            throw new InvalidDelinquencyInputException("history.maxStatus12m", "history.maxStatus12m is " + history.maxStatus12m()
                    + " but the loan is " + daysPastDue + " days past due now");
        }
        if (episodes == 0 && worst > 0) {
            throw new InvalidDelinquencyInputException("history.maxStatus12m", "history.maxStatus12m is " + history.maxStatus12m()
                    + " but history.episodes30Plus is 0");
        }
        if (episodes > 0 && worst == 0) {
            throw new InvalidDelinquencyInputException("history.maxStatus12m", "history.maxStatus12m is CURRENT but "
                    + "history.episodes30Plus is " + episodes);
        }
        if (cure != null && episodes == 0) {
            throw new InvalidDelinquencyInputException("history.monthsSinceCure", "history.monthsSinceCure is given but "
                    + "history.episodes30Plus is 0: there was nothing to cure");
        }
        if (start == 0 && episodes > 0 && cure == null) {
            throw new InvalidDelinquencyInputException("history.monthsSinceCure", "the loan is current and had "
                    + "history.episodes30Plus = " + episodes + ", so it cured within the window: give history.monthsSinceCure");
        }
        int noCure = model.noCureMonths();
        int since = cure == null || cure >= 12 ? noCure : cure;
        return new Route(true, null, start, new DelinquencyModel.History(episodes, since, worst));
    }

    private Route fallback(String reason) {
        return new Route(false, reason, 0, MultiStateEngine.NO_HISTORY);
    }

    private static int indexOf(String status) {
        for (int i = 0; i < STATUS.length; i++) {
            if (STATUS[i].equals(status)) {
                return i;
            }
        }
        throw new InvalidDelinquencyInputException("history.maxStatus12m", "history.maxStatus12m must be CURRENT, D30 or D60");
    }
}
