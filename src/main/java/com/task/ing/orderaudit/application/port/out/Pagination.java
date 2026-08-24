package com.task.ing.orderaudit.application.port.out;

public record Pagination(int page, int size) {

    public Pagination {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1 || size > 500) {
            throw new IllegalArgumentException("size must be between 1 and 500, was: " + size);
        }
    }

    public static Pagination of(int page, int size) {
        return new Pagination(page, size);
    }
}
