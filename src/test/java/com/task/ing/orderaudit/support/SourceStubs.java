package com.task.ing.orderaudit.support;

import com.github.tomakehurst.wiremock.WireMockServer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

public class SourceStubs {

    private final WireMockServer server;
    private final String root;

    public SourceStubs(WireMockServer server, String root) {
        this.server = server;
        this.root = root;
    }

    public SourceStubs aggregate(String orderId, String json) {
        server.stubFor(get(urlPathEqualTo(root + "/" + orderId))
                .willReturn(jsonResponse(200, json)));
        return this;
    }

    public SourceStubs aggregateMissing(String orderId) {
        server.stubFor(get(urlPathEqualTo(root + "/" + orderId))
                .willReturn(aResponse().withStatus(404)));
        return this;
    }

    public SourceStubs aggregateUnavailable(String orderId) {
        server.stubFor(get(urlPathEqualTo(root + "/" + orderId))
                .willReturn(aResponse().withStatus(503).withBody("service unavailable")));
        return this;
    }

    public SourceStubs events(String orderId, String... eventJson) {
        server.stubFor(get(urlPathEqualTo(root + "/" + orderId + "/events"))
                .willReturn(jsonResponse(200, Payloads.jsonArray(eventJson))));
        return this;
    }

    public SourceStubs eventsUnavailable(String orderId) {
        server.stubFor(get(urlPathEqualTo(root + "/" + orderId + "/events"))
                .willReturn(aResponse().withStatus(500)));
        return this;
    }

    public SourceStubs eventCount(String orderId, long count) {
        server.stubFor(get(urlPathEqualTo(root + "/" + orderId + "/events/count"))
                .willReturn(jsonResponse(200,
                        """
                        {"orderId": "%s", "count": %d}
                        """.formatted(orderId, count))));
        return this;
    }

    public SourceStubs modifiedSince(String... orderIds) {
        server.stubFor(get(urlPathEqualTo(root))
                .willReturn(jsonResponse(200,
                        """
                        {"orderIds": [%s], "nextPageToken": null}
                        """.formatted(quoted(orderIds)))));
        return this;
    }

    public SourceStubs modifiedSinceFirstPage(String nextPageToken, String... orderIds) {
        server.stubFor(get(urlPathEqualTo(root))
                .withQueryParam("pageToken", absent())
                .willReturn(jsonResponse(200,
                        """
                        {"orderIds": [%s], "nextPageToken": "%s"}
                        """.formatted(quoted(orderIds), nextPageToken))));
        return this;
    }

    public SourceStubs modifiedSinceNextPage(String pageToken, String... orderIds) {
        server.stubFor(get(urlPathEqualTo(root))
                .withQueryParam("pageToken", equalTo(pageToken))
                .willReturn(jsonResponse(200,
                        """
                        {"orderIds": [%s], "nextPageToken": null}
                        """.formatted(quoted(orderIds)))));
        return this;
    }

    public SourceStubs modifiedSinceUnavailable() {
        server.stubFor(get(urlPathEqualTo(root)).willReturn(aResponse().withStatus(503)));
        return this;
    }

    public SourceStubs healthyOrder(String orderId, String status, String totalAmount, String... eventJson) {
        aggregate(orderId, Payloads.order(orderId, status, totalAmount));
        events(orderId, eventJson);
        eventCount(orderId, eventJson.length);
        return this;
    }

    public int requestsTo(String path) {
        return server.countRequestsMatching(getRequestedFor(urlPathEqualTo(path)).build()).getCount();
    }

    private String quoted(String... values) {
        return java.util.Arrays.stream(values)
                .map(value -> "\"" + value + "\"")
                .collect(java.util.stream.Collectors.joining(","));
    }

    private com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder jsonResponse(
            int status, String body) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }
}
