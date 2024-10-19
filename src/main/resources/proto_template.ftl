syntax = "proto3";

package ${packageName}.${serviceName}Gen;

<#if javaMultipleFiles??>option java_multiple_files = ${javaMultipleFiles?c};</#if>
option java_package = "${packageName}.${serviceName}Gen";
option java_outer_classname = "${serviceName}OuterClass";

service ${serviceName} {
<#list endpoints as endpoint>
    rpc ${endpoint.name} (${endpoint.request}) returns (${endpoint.response}){}
</#list>
}

<#list messages as message>
message ${message.name} {
${message.fields}
}
</#list>

<#list enums as enum>
enum ${enum.name} {
${enum.fields}
}
</#list>