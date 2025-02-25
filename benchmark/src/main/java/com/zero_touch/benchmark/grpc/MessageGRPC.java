package com.zero_touch.benchmark.grpc;

import com.zero_touch.benchmark.services.MessageService;
import com.zero_touch.benchmark.services.MessageServiceGen.MessageServiceGrpc;
import com.zero_touch.benchmark.services.MessageServiceGen.RequestDtoDto;
import com.zero_touch.benchmark.services.MessageServiceGen.ResponseDtoDto;
import io.grpc.stub.StreamObserver;
import org.lognet.springboot.grpc.GRpcService;
import org.springframework.beans.factory.annotation.Autowired;

@GRpcService
public class MessageGRPC extends MessageServiceGrpc.MessageServiceImplBase{
    @Autowired
    private MessageService messageService;

    @Override
    public void getMessage(RequestDtoDto request, StreamObserver<ResponseDtoDto> responseObserver) {
        String text = request.getText();
        int multiplier = (int)request.getMultiplier();

        if (multiplier < 1) {
            ResponseDtoDto response = ResponseDtoDto.newBuilder()
                    .setResponse("Error: Invalid input - multiplier must be >= 1")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            return;
        }

        String repeatedMessage = String.join(" ", java.util.Collections.nCopies(multiplier, text)) + "!";

        ResponseDtoDto response = ResponseDtoDto.newBuilder()
                .setResponse(repeatedMessage)
                .setLength(repeatedMessage.length())
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
