package org.hypen.GRpcServ.models;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.*;

@Getter
@Setter
public class ProtoObject implements Serializable {
    String packageName;
    Boolean javaMultipleFiles = true;
    String serviceName;
    List<Endpoint> endpoints = new ArrayList<>(1);
    Set<Message> messages = new HashSet<>(2);
    Set<String> imports = new HashSet<>(0);
    Map<String, String> dtoMap = new HashMap<>(0);
}
