package org.hypen.GRpcServ.models;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Getter
@Setter
public class ProtoObject implements Serializable {
    String packageName;
    Boolean javaMultipleFiles = true;
    String serviceName;
    List<Endpoint> endpoints = new ArrayList<>(1);
    Set<Message> messages = new HashSet<>(2);
    Set<String> imports = new HashSet<>(0);
}
