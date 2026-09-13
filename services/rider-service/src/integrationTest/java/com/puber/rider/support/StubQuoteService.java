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
 * Stands in for matching-service, built from the same proto the real one implements. See
 * project-context.md, "Own datastores are real. Another service is stubbed."
 *
 * <p>Also an interceptor, because that is the only way to see the headers a call arrived with: the
 * generated base class makes {@code bindService()} final.
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
        // Copied now, because a test reads it after the call has finished.
        Map<String, String> copied = new HashMap<>();
        for (String name : headers.keys()) {
            // Reading a binary header as ASCII throws, and no test here looks at one.
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
