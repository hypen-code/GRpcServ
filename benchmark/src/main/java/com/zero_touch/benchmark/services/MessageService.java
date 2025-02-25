package com.zero_touch.benchmark.services;

import com.zero_touch.benchmark.models.RequestDto;
import com.zero_touch.benchmark.models.ResponseDto;
import org.springframework.stereotype.Service;

@Service
public class MessageService {

    public ResponseDto getMessage(RequestDto request) {
        ResponseDto response = new ResponseDto();

        String repeatedMessage = String
                .join(" ", java.util.Collections.nCopies((int) request.getMultiplier(), request.getText())) + "!";

        response.setResponse(repeatedMessage);
        response.setLength(repeatedMessage.length());
        return response;
    }
}
