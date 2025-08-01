import java.util.concurrent.LinkedBlockingQueue;
/**
 * Worker class to handle queued requests
 */
public class RequestHandler implements Runnable {
    private LinkedBlockingQueue<ClientRequest> queue;
    private ChirpServer server;
    
    public RequestHandler(LinkedBlockingQueue<ClientRequest> queue, ChirpServer server) {
        this.queue = queue;
        this.server = server;
    }

    @Override
    public void run(){ 
        while (true) {
            try {
                //Blocks until a request is available
                ClientRequest request = queue.take();
                server.processRequest(request);
            } catch (InterruptedException e){
                System.out.println("Worked thread has been interrupted: " + e.getMessage());
            }
        }
    }
}
