package com.riemann.agent.infrastructure.execution;

import java.util.Optional;
import java.util.function.Function;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/** Reactor Context helpers for {@link ExecutionContext}. */
public final class ExecutionContextHolder {

    private static final Class<ExecutionContext> KEY = ExecutionContext.class;

    private ExecutionContextHolder() {}

    public static Function<Context, Context> contextWrite(ExecutionContext context) {
        return reactorContext -> reactorContext.put(KEY, context);
    }

    public static Optional<ExecutionContext> current(ContextView view) {
        return view.hasKey(KEY) ? Optional.of(view.get(KEY)) : Optional.empty();
    }
}
