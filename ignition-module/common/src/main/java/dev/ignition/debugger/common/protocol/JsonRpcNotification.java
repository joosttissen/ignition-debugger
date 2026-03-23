package dev.ignition.debugger.common.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A JSON-RPC 2.0 notification (server-to-client push, no {@code id} field).
 *
 * <pre>{@code
 * {
 *   "jsonrpc": "2.0",
 *   "method": "debug.event.stopped",
 *   "params": { "reason": "breakpoint", "threadId": 1 }
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JsonRpcNotification {

    @JsonProperty("jsonrpc")
    private String jsonrpc = "2.0";

    @JsonProperty("method")
    private String method;

    @JsonProperty("params")
    private Object params;

    public JsonRpcNotification() {}

    public JsonRpcNotification(String method, Object params) {
        this.method = method;
        this.params = params;
    }

    public String getJsonrpc() { return jsonrpc; }
    public String getMethod()  { return method; }
    public Object getParams()  { return params; }

    public void setJsonrpc(String jsonrpc) { this.jsonrpc = jsonrpc; }
    public void setMethod(String method)   { this.method = method; }
    public void setParams(Object params)   { this.params = params; }
}
