package org.runetekk;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Client.java
 * @version 1.0.0
 * @author RuneTekk Development (SiniSoul)
 */
public final class Client {
    
    /**
     * The amount of time the {@link Client} has to parse the request
     * before it ultimately times out.
     */
    long requestTimeout;
    
    /**
     * The {@link InputStream} of this client.
     */
    InputStream inputStream;
    
    /**
     * The {@link OutputStream} of this client.
     */
    OutputStream outputStream;
    
    /**
     * The accumulated request buffer.
     */
    byte[] requestAcc;
    
    /**
     * The request id.
     */
    int requestId;
    
    /**
     * The current offset.
     */
    int offset;
    
    /**
     * Destroys this {@link Client}.
     */
    public void destroy() {
        try {
            inputStream.close();
            outputStream.close();
        } catch(Exception ex) {}
        requestAcc = null;
    }
    
    /**
     * Constructs a new {@link Client};
     * @param socket The socket to create the client from.
     */
    public Client(Socket socket) throws IOException {
        this.inputStream = socket.getInputStream();
        this.outputStream = socket.getOutputStream();
        requestId = -1;
    } 
}
