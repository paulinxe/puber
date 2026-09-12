package com.puber.rider.support;

import com.puber.contracts.quote.v1.GetQuoteRequest;
import com.puber.contracts.quote.v1.GetQuoteResponse;
import com.puber.contracts.quote.v1.QuoteServiceGrpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.HashMap;
import java.util.Map;
import org.springframework.grpc.server.GlobalServerInterceptor;

/**
 * matching-service, stood up in-process from the same {@code contracts/proto} the real peer
 * implements -- so the request and response shapes cannot drift without a compile error.
 *
 * <p>project-context.md, "Own datastores are real. Another service is stubbed.": a suite that only
 * passes because a sibling service happens to be running cannot move to that service's own
 * repository. What it costs is named in the story's Honest limits and closed by one end-to-end test
 * at PUB-4-3.
 *
 * <p>It records the metadata it received, because a receiver that saw the header is stronger
 * evidence than a sender that attached one. That has to come from an interceptor: the generated
 * base class declares {@code bindService()} final, so a service implementation cannot wrap itself.
 */
@GlobalServerInterceptor
public class StubQuoteService extends QuoteServiceGrpc.QuoteServiceImplBase
        implements ServerInterceptor {

    private volatile GetQuoteResponse answer = GetQuoteResponse.getDefaultInstance();

    private volatile Status rejection;

    private volatile GetQuoteRequest received;

    private volatile Map<String, String> receivedMetadata = Map.of();

    @Override
    public void getQuote(
            GetQuoteRequest request, StreamObserver<GetQuoteResponse> responseObserver) {
        received = request;
        if (rejection != null) {
            responseObserver.onError(rejection.asRuntimeException());
            return;
        }
        responseObserver.onNext(answer);
        responseObserver.onCompleted();
    }

    /** The context is cached across test classes, so every test sets its own answer. */
    public void reset() {
        answer = GetQuoteResponse.getDefaultInstance();
        rejection = null;
        received = null;
        receivedMetadata = Map.of();
    }

    public void answerWith(long fareMinorUnits, long distanceMetres) {
        answer =
                GetQuoteResponse.newBuilder()
                        .setFareMinorUnits(fareMinorUnits)
                        .setDistanceMetres(distanceMetres)
                        .build();
    }

    public void answerWith(long fareMinorUnits, long distanceMetres, long etaMinutes) {
        answer =
                GetQuoteResponse.newBuilder()
                        .setFareMinorUnits(fareMinorUnits)
                        .setDistanceMetres(distanceMetres)
                        .setEtaMinutes(etaMinutes)
                        .build();
    }

    public void rejectWith(Status status) {
        rejection = status;
    }

    public GetQuoteRequest received() {
        return received;
    }

    public String receivedMetadata(Metadata.Key<String> key) {
        return receivedMetadata.get(key.originalName());
    }

    @Override
    public <R, S> ServerCall.Listener<R> interceptCall(
            ServerCall<R, S> call, Metadata headers, ServerCallHandler<R, S> next) {
        // Copied out, not retained: gRPC does not promise a Metadata instance stays valid once the
        // call completes, and this is read after the response has come back.
        Map<String, String> copied = new HashMap<>();
        for (String name : headers.keys()) {
            // Metadata.Key.of rejects a "-bin" name with the ASCII marshaller, and nothing here
            // asserts on a binary header.
            if (name.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
                continue;
            }
            String value = headers.get(Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER));
            if (value != null) {
                copied.put(name, value);
            }
        }
        receivedMetadata = Map.copyOf(copied);
        return next.startCall(call, headers);
    }
}
