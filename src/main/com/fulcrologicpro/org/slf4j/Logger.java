package com.fulcrologicpro.org.slf4j;

import java.util.Arrays;

public class Logger {
    private final Class source;

    public <T> Logger(Class<T> c) {
        this.source = c;
    }

    public void trace(String msg, Object... args) {
        if (System.getProperty("trace") != null) {
            System.out.println(msg);
            Arrays.stream(args).forEach(e -> System.err.println("  " + e.toString()));
        }
    }

    public void info(Object... args) {
    }

    public void error(String msg, Object... args) {
        System.err.println(msg);
        Arrays.stream(args).forEach(e -> System.err.println("  " + e.toString()));
    }

    public void debug(Object... args) {
    }

    public boolean isTraceEnabled() {
        return false;
    }
}
