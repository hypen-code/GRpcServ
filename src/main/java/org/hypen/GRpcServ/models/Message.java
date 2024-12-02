package org.hypen.GRpcServ.models;

import lombok.*;

import java.io.Serializable;

@Getter
@Setter
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public class Message implements Serializable {
    Type type;
    String name;
    String fields;

    public static enum Type {
        GRpcMessage,
        GRpcEnum
    }
}