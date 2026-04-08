/**
 * Base classes used by concrete Parser and Generator implementations;
 * contain functionality that is not specific to JSON or input
 * abstraction (byte vs char).
 * Most formats extend these types, although it is also possible to
 * directly extend {@link com.fulcrologicpro.com.fasterxml.jackson.core.JsonParser} or
 * {@link com.fulcrologicpro.com.fasterxml.jackson.core.JsonGenerator}.
 */
package com.fulcrologicpro.com.fasterxml.jackson.core.base;
