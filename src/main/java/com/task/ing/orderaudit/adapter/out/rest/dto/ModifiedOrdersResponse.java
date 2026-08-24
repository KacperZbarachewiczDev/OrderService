package com.task.ing.orderaudit.adapter.out.rest.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ModifiedOrdersResponse(List<String> orderIds, String nextPageToken) {

    public ModifiedOrdersResponse {
        orderIds = orderIds == null ? List.of() : List.copyOf(orderIds);
    }
}
