package com.riemann.agent.infrastructure.seekdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.riemann.agent.infrastructure.seekdb.sql.SqlGuard;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import reactor.test.StepVerifier;

/** The hybrid search SQL must never scan rows belonging to other users (issue #19). */
class HybridSearchScopingTest {

    private static SeekDbConversationRepository repository(JdbcTemplate jdbc) {
        return new SeekDbConversationRepository(
                jdbc, mock(SqlGuard.class), mock(PlatformTransactionManager.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sqlIsScopedByUserIdAndBindsItAsAParameter() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        StepVerifier.create(repository(jdbc).hybridSearch("alice", "[1,2]", null, 1.4, 5))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), params.capture());
        assertThat(sql.getValue()).contains("c.user_id = ?");
        assertThat(List.of(params.getValue())).contains("alice");
    }

    @Test
    void blankUserIdIsRejectedBeforeAnyQuery() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StepVerifier.create(repository(jdbc).hybridSearch(" ", "[1,2]", null, 1.4, 5))
                .verifyError(IllegalArgumentException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void keywordVariantStillScopesByUserId() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        StepVerifier.create(repository(jdbc).hybridSearch("bob", "[1,2]", "refund", 1.4, 5))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), params.capture());
        assertThat(sql.getValue()).contains("MATCH(c.content) AGAINST");
        assertThat(sql.getValue()).contains("c.user_id = ?");
        assertThat(List.of(params.getValue())).contains("bob", "refund");
    }
}
