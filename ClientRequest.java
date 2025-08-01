import java.net.Socket;

/**
 * Data class to represent a Client Request
 */
public class ClientRequest {
    
    private Socket socket;
    private String method;
    private String requestUri;
    private String headers;
    private String requestBody;

    public ClientRequest(Socket socket, String method, String requestUri, String headers, String requestBody) {
        this.socket = socket;
        this.method = method;
        this.requestUri = requestUri;
        this.headers = headers;
        this.requestBody = requestBody;
    }

    public Socket getSocket() { return socket; }
    public String getMethod() { return method; }
    public String getRequestUri() { return requestUri; }
    public String getHeaders() { return headers; }
    public String getRequestBody() { return requestBody; }

}
