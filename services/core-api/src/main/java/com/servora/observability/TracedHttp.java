package com.servora.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.RequestReplySenderContext;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Sends a request to another Servora service inside an observation, so the call is timed (metric
 * {@code servora.internal.requests}) and carries the W3C trace context ({@code traceparent}) that
 * the receiving service continues.
 *
 * <p>Only the target service's name and the response status are recorded: never the URL's query,
 * the headers (which hold the signed internal token) or the body.
 */
public final class TracedHttp {

    public static final String OBSERVATION = "servora.internal.requests";

    private TracedHttp() {}

    public static <T> HttpResponse<T> send(
            ObservationRegistry registry,
            String target,
            HttpClient http,
            HttpRequest.Builder request,
            HttpResponse.BodyHandler<T> body)
            throws IOException, InterruptedException {
        RequestReplySenderContext<HttpRequest.Builder, HttpResponse<?>> context =
                new RequestReplySenderContext<>((carrier, key, value) -> {
                    if (carrier != null) {
                        carrier.setHeader(key, value);
                    }
                });
        context.setCarrier(request);
        context.setRemoteServiceName(target);
        Observation observation = Observation.createNotStarted(OBSERVATION, () -> context, registry)
                .contextualName("POST " + target)
                .lowCardinalityKeyValue("target", target);
        observation.start();
        try (Observation.Scope ignored = observation.openScope()) {
            HttpResponse<T> response = http.send(request.build(), body);
            context.setResponse(response);
            observation.lowCardinalityKeyValue("status", String.valueOf(response.statusCode()));
            return response;
        } catch (IOException | InterruptedException | RuntimeException e) {
            observation.lowCardinalityKeyValue("status", "IO_ERROR");
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }
}
