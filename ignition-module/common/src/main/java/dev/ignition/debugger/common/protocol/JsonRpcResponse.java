package dev.ignition.debugger.common.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A JSON-RPC 2.0 response message.
 *
 * <pre>{@code
 * // Success
 * { "jsonrpc": "2.0", "id": 1, "result": { ... } }
 *
 * // Error
 * { "jsonrpc": "2.0", "id": 1, "error": { "code": -32600, "message": "..." } }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JsonRpcResponse {

    @JsonProperty("jsonrpc")
    private String jsonrpc = "2.0";

    @JsonProperty("id")
    private Object id;

    @JsonProperty("result")
    private Object result;

    @JsonProperty("error")
    private ErrorObject error;

    public JsonRpcResponse() {}

    /** Construct a success response. */
    public static JsonRpcResponse success(Object id, Object result) {
        JsonRpcResponse r = new JsonRpcResponse();
        r.id = id;
        r.result = result;
        return r;
    }

    /** Construct an error response. */
    public static JsonRpcResponse error(Object id, int code, String message) {
        JsonRpcResponse r = new JsonRpcResponse();
        r.id = id;
        r.error = new ErrorObject(code, message);
        return r;
    }

    public String getJsonrpc() { return jsonrpc; }
    public Object getId()      { return id; }
    public Object getResult()  { return result; }
    public ErrorObject getError() { return error; }

    public void setJsonrpc(String jsonrpc) { this.jsonrpc = jsonrpc; }
    public void setId(Object id)           { this.id = id; }
    public void setResult(Object result)   { this.result = result; }
    public void setError(ErrorObject error){ this.error = error; }

    /** JSON-RPC error object. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorObject {
        @JsonProperty("code")    private int    code;
        @JsonProperty("message") private String message;
        @JsonProperty("data")    private Object data;

        public ErrorObject() {}
        public ErrorObject(int code, String message) {
            this.code = code;
            this.message = message;
        }

        public int    getCode()    { return code; }
        public String getMessage() { return message; }
        public Object getData()    { return data; }

        public void setCode(int code)       { this.code = code; }
        public void setMessage(String msg)  { this.message = msg; }
        public void setData(Object data)    { this.data = data; }
    }
}
