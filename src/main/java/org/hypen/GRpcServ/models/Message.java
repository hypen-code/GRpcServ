package org.hypen.GRpcServ.models;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

@Getter
@Setter
@AllArgsConstructor
@EqualsAndHashCode
public class Message implements Serializable {
    Type type;
    String name;
    String fields;

    public static enum Type {
        GRpcMessage,
        GRpcEnum
    }
}