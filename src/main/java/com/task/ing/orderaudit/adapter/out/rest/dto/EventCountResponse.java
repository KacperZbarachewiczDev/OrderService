package com.task.ing.orderaudit.adapter.out.rest.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EventCountResponse(String orderId, long count) {

}
