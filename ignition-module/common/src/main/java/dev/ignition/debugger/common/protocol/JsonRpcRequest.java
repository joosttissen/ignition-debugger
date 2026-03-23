package dev.ignition.debugger.common.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A JSON-RPC 2.0 request message.
 *
 * <pre>{@code
 * {
 *   "jsonrpc": "2.0",
 *   "id": 1,
 *   "method": "debug.startSession",
 *   "params": { ... }
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JsonRpcRequest {

    @JsonProperty("jsonrpc")
    private String jsonrpc = "2.0";

    @JsonProperty("id")
    private Object id;

    @JsonProperty("method")
    private String method;

    @JsonProperty("params")
    private Object params;

    public JsonRpcRequest() {}

    public JsonRpcRequest(Object id, String method, Object params) {
        this.id = id;
        this.method = method;
        this.params = params;
    }

    public String getJsonrpc() { return jsonrpc; }
    public Object getId()      { return id; }
    public String getMethod()  { return method; }
    public Object getParams()  { return params; }

    public void setJsonrpc(String jsonrpc) { this.jsonrpc = jsonrpc; }
    public void setId(Object id)           { this.id = id; }
    public void setMethod(String method)   { this.method = method; }
    public void setParams(Object params)   { this.params = params; }
}
