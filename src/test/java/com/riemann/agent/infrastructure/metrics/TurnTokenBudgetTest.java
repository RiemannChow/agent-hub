package com.riemann.agent.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TurnTokenBudgetTest {

    @Test
    void zeroLimitNeverSignalsExceeded() {
        TurnTokenBudget budget = new TurnTokenBudget(0);

        assertThat(budget.enabled()).isFalse();
        assertThat(budget.record(1_000_000)).isFalse();
        assertThat(budget.record(1_000_000)).isFalse();
        assertThat(budget.exceeded()).isFalse();
        assertThat(budget.used()).isEqualTo(2_000_000);
    }

    @Test
    void signalsOnlyWhenCrossingTheLimit() {
        TurnTokenBudget budget = new TurnTokenBudget(100);

        assertThat(budget.record(60)).isFalse();
        assertThat(budget.record(40)).isFalse(); // exactly at the limit: still allowed
        assertThat(budget.exceeded()).isFalse();
        assertThat(budget.record(1)).isTrue();
        assertThat(budget.exceeded()).isTrue();
        assertThat(budget.record(50)).isFalse(); // already signalled, no repeat
        assertThat(budget.used()).isEqualTo(151);
    }

    @Test
    void negativeLimitIsTreatedAsUnlimited() {
        TurnTokenBudget budget = new TurnTokenBudget(-5);

        assertThat(budget.limit()).isZero();
        assertThat(budget.record(10)).isFalse();
        assertThat(budget.exceeded()).isFalse();
    }

    @Test
    void nonPositiveTokensAreIgnored() {
        TurnTokenBudget budget = new TurnTokenBudget(1);

        assertThat(budget.record(0)).isFalse();
        assertThat(budget.record(-10)).isFalse();
        assertThat(budget.used()).isZero();
    }
}
