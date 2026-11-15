package com.collabcode.execution.model;

/**
 * Fallback representation when a source object carries an unrecognized or missing kind.
 * Used by Jackson defaultImpl so deserialization preserves context for validation.
 */
public class UnknownSource extends RunSource {
}

