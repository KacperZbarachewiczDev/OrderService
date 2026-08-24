package com.task.ing.orderaudit.adapter.in.rest;

public class IssueNotFoundException extends RuntimeException {

    public IssueNotFoundException(String orderId) {
        super("no audit issue recorded for order " + orderId);
    }
}
