package com.example.service;

public interface Validator<T> {

    boolean validate(T item);

    String getErrorMessage();

    default boolean validateAndLog(T item) {
        boolean result = validate(item);
        if (!result) {
            System.out.println("Validation failed: " + getErrorMessage());
        }
        return result;
    }
}
