package org.runetekk;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.CRC32;

/**
 * Main.java
 * @version 1.0.0
 * @author RuneTekk Development (SiniSoul)
 */
public final class Main implements Runnable {
    
    /**
     * The {@link Logger} utility.
     */
    private final static Logger LOGGER = Logger.getLogger(Main.class.getName());
    
    /**
     * The size of a block when writing a file to the socket.
     */
    private final static int BLOCK_SIZE = 1000;
    
    /**
     * The main handler.
     */
    private static Main main;
    
    /**
     * The {@link DirectBuffer} array for all the loaded archives that
     * the client will download.
     */
    private static DirectBuffer[] archiveBuffers;
    
    /**
     * The size of each archive that the client will download.
     */
    private static int[] archiveSizes;
    
    /**
     * The names of all the archives that the client will download.
     */
    private static String[] archiveNames;
    
    /**
     * The {@link Client} queue.
     */
    private Deque<Client> clientQueue;
    
    /**
     * The local thread.
     */
    private Thread thread;
    
    /**
     * The local thread is currently paused.
     */
    private boolean isPaused;
    
    /**
     * The {@link ServerSocket} to accept connections from.
     */
    private ServerSocket serverSocket;
    
    /**
     * Prints the application tag.
     */
    private static void printTag() {
        System.out.println(""
        + "                     _____               _______   _    _                         "
        + "\n                    |  __ \\             |__   __| | |  | |                       "
        + "\n                    | |__) |   _ _ __   ___| | ___| | _| | __                     "
        + "\n                    |  _  / | | | '_ \\ / _ \\ |/ _ \\ |/ / |/ /                  "
        + "\n                    | | \\ \\ |_| | | | |  __/ |  __/   <|   <                    "
        + "\n                    |_|  \\_\\__,_|_| |_|\\___|_|\\___|_|\\_\\_|\\_\\             "
        + "\n----------------------------------------------------------------------------------"
        + "\n                               JAGGRAB Server 1.0.0                               "
        + "\n                                 See RuneTekk.com                                 "
        + "\n                               Created by SiniSoul                                "
        + "\n----------------------------------------------------------------------------------");
    }
    
    public static int getRequestId(Client client) {
        try {
            int avail = client.inputStream.available();
            if(avail <= 0)
                return -1;
            int accSize = client.requestAcc != null ? client.requestAcc.length + avail : avail;
            byte[] request = new byte[accSize];
            if(client.requestAcc != null) {
                System.arraycopy(client.requestAcc, 0, request, 0, client.requestAcc.length);
            }
            int read;
            for(int off = 0; off < avail; off += read) {
                read = client.inputStream.read(request, client.requestAcc == null ? 0 : client.requestAcc.length, avail - off);
                if(read < 0)
                    return -1;
            }
            client.requestAcc = request;
            String req = new String(request);
            if(req.startsWith("JAGGRAB /"))
                req = req.substring(9, req.endsWith("\n\n") ? req.length() - 2 : req.length());
            for(int i = 0; i < archiveNames.length; i++) {
                System.out.println(archiveNames[i]);
                if(archiveNames[i] != null && (i == archiveNames.length - 1 ? 
                                                    req.startsWith(archiveNames[i]) : 
                                                    req.equals(archiveNames[i])))
                    return i;
            }
            return -1;
        } catch(Exception ex) {
            return -1;
        }
    }
    
    /**
     * Initializes the local thread.
     */
    private void initialize() {
        thread = new Thread(this);
        thread.start();
    }
    
    /**
     * Destroys this local application.
     */
    private void destroy() {
        if(!isPaused)  {
            if(thread != null) {
                synchronized(this) {
                    isPaused = true;
                    notifyAll();
                }
                try {
                    thread.join();
                } catch(InterruptedException ex) {
                }
            }
            thread = null;
        }
    }
      
    @Override
    public void run() {
        for(;;) {
            synchronized(this) {
                if(isPaused)
                    break;
                Client client = null;
                try {
                     Socket socket = serverSocket.accept();
                     client = new Client(socket);
                } catch(IOException ex) {
                    if(!(ex instanceof SocketTimeoutException))
                        destroy();
                }     
                if(client != null) {   
                    client.requestTimeout = System.currentTimeMillis() + 5000L;
                    synchronized(clientQueue) {
                        clientQueue.add(client);
                    }
                }   
                Client firstClient = null;
                for(int i = 0; i < 10; i++) {
                    client = clientQueue.poll();
                    if(client == null)
                        break;
                    if(firstClient == null)
                        firstClient = client;
                    else if(firstClient != client) {
                        clientQueue.addLast(client);
                        break;
                    }
                    if(client.requestId < 0) {
                        client.requestId = getRequestId(client);
                        if(client.requestId >= 0) {
                            client.requestTimeout = -1L;
                        } else if(client.requestTimeout < System.currentTimeMillis()) {
                            LOGGER.log(Level.SEVERE, "Request timeout!");
                            client.destroy();
                            continue;
                        }
                    }
                    if(client.requestId >= 0) {
                        int write = archiveSizes[client.requestId] - client.offset;
                        if(write > BLOCK_SIZE)
                            write = BLOCK_SIZE;
                        try {
                            client.outputStream.write(archiveBuffers[client.requestId].get(client.offset, write), 0, write);
                        } catch(Exception ex) {
                            LOGGER.log(Level.SEVERE, "Error : {0}", ex);
                            continue;
                        }
                        client.offset += write;
                        if(client.offset == archiveSizes[client.requestId]) {
                            client.destroy();
                            continue;
                        }
                    }
                    clientQueue.addLast(client);
                }
            }
        }
    }
    
    /**
     * The main starting point for this application.
     * @param args The command line arguments.
     */
    public static void main(String[] args) {
        args = args.length == 0 ? new String[] { "server", "server.properties" } : args;
        if(args.length != 2) {
            LOGGER.log(Level.SEVERE, "Invalid setup parameters : <mode> <properties>");
            throw new RuntimeException();
        }
        if(!args[0].equals("setup") && !args[0].equals("server")) {
            LOGGER.log(Level.SEVERE, "Unknown application mode : {0}!", args[0]);
            throw new RuntimeException();
        }
        printTag();
        Properties serverProperties = new Properties();
        try {
            serverProperties.load(new FileReader(args[1]));
        } catch(Exception ex) {
            LOGGER.log(Level.SEVERE, "Exception thrown while loading the properties : {0}", ex);
            throw new RuntimeException();
        }
        boolean updateProperties = false;
        String mainFile = serverProperties.getProperty("MAINFILE");
        if(mainFile == null) {
            LOGGER.log(Level.SEVERE, "MAINFILE property key is null!");
            throw new RuntimeException();
        }
        String indexFile = serverProperties.getProperty("INDEXFILE");
        if(mainFile == null) {
            LOGGER.log(Level.SEVERE, "INDEXFILE property key is null!");
            throw new RuntimeException();
        }
        int indexId = -1;
        try {
           indexId = Integer.parseInt(serverProperties.getProperty("INDEXID")); 
        } catch(Exception ex) {
            LOGGER.log(Level.SEVERE, "Exception thrown while parsing the INDEXID : {0}", ex);
            throw new RuntimeException();
        }
        int portOff = -1;
        try {
           portOff = Integer.parseInt(serverProperties.getProperty("PORTOFF")); 
        } catch(Exception ex) {
            LOGGER.log(Level.SEVERE, "Exception thrown while parsing the PORTOFF : {0}", ex);
            throw new RuntimeException();
        }
        int[] archiveIds = null;
        try {
            String[] archives  = serverProperties.getProperty("ARCHIVES").split("[:]");
            archiveIds = new int[archives.length];
            for(int i = 0; i < archives.length; i++) {
                archiveIds[i] = Integer.parseInt(archives[i]);
            }
        } catch(Exception ex) {
            LOGGER.log(Level.SEVERE, "Exception thrown while parsing the listed archives : {0}", ex);
            throw new RuntimeException();
        }
        String[] names = new String[archiveIds.length];
        for(int i = 0; i < names.length; i++) {
            names[i] = serverProperties.getProperty("ARCHIVE-" + archiveIds[i]);
            if(names[i] == null) {
                LOGGER.log(Level.SEVERE, "Archive {0} name is null!", i);
                throw new RuntimeException();
            }
        }
        String outputDir = serverProperties.getProperty("OUTDIR");
        if(outputDir == null) {
            outputDir = "./";
            serverProperties.put("OUTDIR", outputDir);
            updateProperties = true;
        }
        String crcArchive = serverProperties.getProperty("ARCHIVE-CRC");
        if(crcArchive == null) {
            crcArchive = "crc";
            serverProperties.put("ARCHIVE-CRC", outputDir);
            updateProperties = true;
        }
        if(updateProperties) {
            try {
                serverProperties.store(new FileWriter(args[1]), "Updated by the Application");
            } catch(Exception ex) {
                LOGGER.log(Level.WARNING, "Exception thrown while storing the properties : {0}", ex);
            }
        }
        serverProperties = null;
        if(args[0].equals("setup")) {
            FileIndex fileIndex = null;
            try {
                fileIndex = new FileIndex(indexId + 1, new java.io.RandomAccessFile(mainFile, "r"), 
                                                       new java.io.RandomAccessFile(indexFile, "r"));
            } catch(Exception ex) {
                LOGGER.log(Level.SEVERE, "Exception thrown while initializing the file index : {0}", ex);
                throw new RuntimeException();
            }  
            CRC32 crc32 = new CRC32();
            int[] archiveChecksums = new int[archiveIds.length];
            OutputStream os = null;
            for(int i = 0; i < archiveIds.length; i++) {
                byte[] data = fileIndex.get(archiveIds[i]);
                if(data == null) {
                    LOGGER.log(Level.SEVERE, "Error while getting archive {0} from the file index!", archiveIds[i]);
                    throw new RuntimeException();
                }
                crc32.reset();
                crc32.update(data);
                archiveChecksums[i] = (int) crc32.getValue();
                try {
                    os = new FileOutputStream(outputDir + names[i]);
                    os.write(data);
                    os.flush();
                    os.close();
                } catch(Exception ex) {
                    LOGGER.log(Level.SEVERE, "Error while writing archive to a file : {0}", ex);
                    throw new RuntimeException();
                }
            }
            fileIndex.destroy();
            int checksumCheck = (1234 << 1) + 0;
            for(int i = 0; i < archiveChecksums.length; i++)
                checksumCheck = (checksumCheck << 1) + archiveChecksums[i];
            try {
                os = new DataOutputStream(new FileOutputStream(outputDir + crcArchive));
                ((DataOutputStream) os).writeInt(0);
                for(int i = 0; i < archiveChecksums.length; i++)
                    ((DataOutputStream) os).writeInt(archiveChecksums[i]);
                ((DataOutputStream) os).writeInt(checksumCheck);
                os.flush();
                os.close();
            } catch(Exception ex) {
                LOGGER.log(Level.SEVERE, "Error while writing crc archive to a file : {0}", ex);
                throw new RuntimeException();
            }
            return;
        } else if(args[0].equals("server")) {
            int maximumEntryId = 0;
            for(int i = 0; i < archiveIds.length; i++) {
                if(maximumEntryId < archiveIds[i])
                    maximumEntryId = archiveIds[i];
            }
            archiveBuffers = new DirectBuffer[maximumEntryId + 1 + 1];
            archiveNames = new String[maximumEntryId + 1 + 1];
            archiveSizes = new int[maximumEntryId + 1 + 1];
            for(int i = 0; i < archiveIds.length; i++) {
                try {
                    archiveNames[archiveIds[i]] = names[i];
                    DataInputStream is = new DataInputStream(new FileInputStream(outputDir + names[i]));
                    byte[] src = new byte[is.available()];
                    is.readFully(src);
                    archiveSizes[archiveIds[i]] = src.length;
                    DirectBuffer buffer = archiveBuffers[archiveIds[i]] = new DirectBuffer();
                    buffer.put(src);
                    is.close();
                } catch(Exception ex) {
                    LOGGER.log(Level.SEVERE, "Error while reading archive {0} : {1}", new Object[] { i, ex });
                    throw new RuntimeException();
                }
            }
            try {
                archiveNames[archiveNames.length - 1] = crcArchive;
                DataInputStream is = new DataInputStream(new FileInputStream(outputDir + crcArchive));
                byte[] src = new byte[is.available()];
                is.readFully(src);
                archiveSizes[archiveSizes.length - 1] = src.length;
                int off = 0;
                for(int i = 4; off < archiveIds.length; i += 4) {
                    archiveNames[archiveIds[off++]] += ((src[i] & 0xFF) << 24)     | 
                                                       ((src[i + 1] & 0xFF) << 16) | 
                                                       ((src[i + 2] & 0xFF) << 8)  | 
                                                        (src[i + 3] & 0xFF);
                }
                DirectBuffer buffer = archiveBuffers[archiveBuffers.length - 1] = new DirectBuffer();
                buffer.put(src);
                is.close();
            } catch(Exception ex) {
                LOGGER.log(Level.SEVERE, "Exception thrown while loading the packed CRC : {0}", ex);
                throw new RuntimeException();
            }
            main = new Main(portOff);
        }
    }
    
    /**
     * Prevent construction;
     */
    private Main(int portOff) {
        try {
            clientQueue = new LinkedList<Client>();
            serverSocket = new ServerSocket();
            serverSocket.setSoTimeout(5);
            serverSocket.bind(new InetSocketAddress(43594 + portOff));
            initialize();
        } catch(Exception ex) {
            LOGGER.log(Level.SEVERE, "Exception thrown while initializing : {0}", ex);
            throw new RuntimeException();
        }
    }
}
