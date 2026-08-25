package com.wealth.portfolio.composition;

import java.util.List;

/**
 * Materialises complete desired holding tuples after the version precondition has locked the
 * snapshot. Invoked only inside {@link HoldingReplacementService}.
 */
@FunctionalInterface
public interface TuplePreparer {

    List<DesiredHoldingState> materialise(List<RawIntent> intent, List<HoldingSnapshot> lockedSnapshot);
}
