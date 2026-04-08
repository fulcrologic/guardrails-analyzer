package com.fulcrologicpro.org.slf4j;

public class LoggerFactory {
    public static <T> Logger getLogger(Class<T> c) {
        return new Logger(c);
    }
}
