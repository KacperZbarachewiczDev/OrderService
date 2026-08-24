package com.task.ing.orderaudit.domain.audit;

public enum Severity {

    MINOR,
    MAJOR,
    CRITICAL;

    public boolean isAtLeast(Severity other) {
        return compareTo(other) >= 0;
    }

    public static Severity max(Severity left, Severity right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.compareTo(right) >= 0 ? left : right;
    }
}
