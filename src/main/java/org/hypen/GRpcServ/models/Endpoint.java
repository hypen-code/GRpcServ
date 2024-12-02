package org.hypen.GRpcServ.models;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.Map;

@Getter
@Setter
@ToString
@AllArgsConstructor
public class Endpoint implements Serializable {
    String name;
    Message request;
    Message response;
    Map<String, String> params;
}
