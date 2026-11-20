package com.riemann.agent.infrastructure.context;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenCounterTest {

    @Test
    void heuristicCountsChineseAsOneTokenPerCharacter() {
        assertThat(new HeuristicTokenCounter().count("你好世界")).isEqualTo(4);
    }

    @Test
    void heuristicApproximatesEnglishAtFourCharsPerToken() {
        assertThat(new HeuristicTokenCounter().count("hello world")).isEqualTo(3);
    }

    @Test
    void emptyTextHasZeroTokens() {
        assertThat(new HeuristicTokenCounter().count("")).isZero();
        assertThat(new HeuristicTokenCounter().count(null)).isZero();
    }

    @Test
    void charModeUsesUniformApproximation() {
        assertThat(new CharApproxTokenCounter().count("abcdefgh")).isEqualTo(2);
    }
}
