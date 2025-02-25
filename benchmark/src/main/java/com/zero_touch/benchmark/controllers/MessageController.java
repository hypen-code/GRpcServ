package com.zero_touch.benchmark.controllers;

import com.zero_touch.benchmark.models.RequestDto;
import com.zero_touch.benchmark.models.ResponseDto;
import com.zero_touch.benchmark.services.MessageService;
import org.hypen.GRpcServ.annotations.GRpcServ;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/get-message")
public class MessageController {
    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @GRpcServ
    @PostMapping
    public ResponseDto getMessage(@RequestBody RequestDto message) {
        return messageService.getMessage(message);
    }
}
