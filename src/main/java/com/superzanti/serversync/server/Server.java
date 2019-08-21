package com.superzanti.serversync.server;

import com.superzanti.serversync.ServerSync;
import com.superzanti.serversync.client.ClientWorker;
import com.superzanti.serversync.gui.FileProgress;
import com.superzanti.serversync.util.AutoClose;
import com.superzanti.serversync.util.Logger;
import com.superzanti.serversync.util.enums.EBinaryAnswer;
import com.superzanti.serversync.util.enums.EServerMessage;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;



/**
 * Interacts with a server running serversync
 *
 * @author Rheimus
 */
public class Server {

    public final String IP_ADDRESS;
    public final int PORT;
    private ObjectOutputStream oos = null;
    private ObjectInputStream ois = null;
    private Socket clientSocket = null;
    private InetAddress host = null;
    private EnumMap<EServerMessage, String> SCOMS;

    public Server(ClientWorker caller, String ip, int port) {
        IP_ADDRESS = ip;
        PORT = port;
    }

    @SuppressWarnings("unchecked")
    public boolean connect() {
        try {
            host = InetAddress.getByName(IP_ADDRESS);
        } catch (UnknownHostException e) {
            Logger.error(ServerSync.strings.getString("connection_failed_host") + ": " + IP_ADDRESS);
            return false;
        }

        Logger.debug(ServerSync.strings.getString("connection_attempt_server"));
        clientSocket = new Socket();

        Logger.log("< " + ServerSync.strings.getString("connection_message") + " >");
        try {
            clientSocket.connect(new InetSocketAddress(host.getHostName(), PORT), 5000);
        } catch (IOException e) {
            Logger.error(ServerSync.strings.getString("connection_failed_server") + ": " + IP_ADDRESS + ":" + PORT);
            AutoClose.closeResource(clientSocket);
            return false;
        }

        // write to socket using ObjectOutputStream
        Logger.debug(ServerSync.strings.getString("debug_IO_streams"));
        try {
            oos = new ObjectOutputStream(clientSocket.getOutputStream());
            ois = new ObjectInputStream(clientSocket.getInputStream());
        } catch (IOException e) {
            Logger.debug(ServerSync.strings.getString("debug_IO_streams_failed"));
            AutoClose.closeResource(clientSocket);
            return false;
        }

        try {
            oos.writeObject(ServerSync.HANDSHAKE);
        } catch (IOException e) {
            Logger.outputError(ServerSync.HANDSHAKE);
        }

        try {
            SCOMS = (EnumMap<EServerMessage, String>) ois.readObject();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            Logger.inputError(e.getMessage());
        }

        System.out.println(SCOMS);

        return true;
    }

    public List<String> fetchManagedDirectories() {
        Logger.debug("Fetching managed directories from server");
        String message = SCOMS.get(EServerMessage.GET_MANAGED_DIRECTORIES);

        try {
            oos.writeObject(message);
            oos.flush();

            @SuppressWarnings("unchecked")
            List<String> directories = (List<String>) ois.readObject();
            return directories;
        } catch (IOException e) {
            Logger.debug("Failed to write object (" + message + ") to client output stream");
        } catch (ClassCastException | ClassNotFoundException e) {
            Logger.debug("Unexpected object read from input stream");
            Logger.debug(e);
        }
        return null;
    }

    public int fetchNumberOfServerManagedFiles() {
        Logger.debug("Fetching number of managed files from server");
        String message = SCOMS.get(EServerMessage.GET_NUMBER_OF_MANAGED_FILES);
        try {
            oos.writeObject(message);
            oos.flush();

            return ois.readInt();
        } catch (IOException e) {
            Logger.debug(e);
        }
        return -1;
    }

    public Map<String, String> syncFiles(Map<String, String> clientFiles, VoidFunction guiUpdate) {
        // Server: Do you have this file?
        // - String: path
        // - String: hash
        // Client: yes | no (bit)
        // Server (yes) - skip to next file
        // Server (no) - send file
        Map<String, String> clientFilesCopy = new HashMap<>(clientFiles);
        boolean didSyncFiles = false;
        try {
            String message = SCOMS.get(EServerMessage.SYNC_FILES);
            oos.writeObject(message);
            oos.flush();

            // While I have more files to process...
            while (ois.readBoolean()) {
                // Server: Do you have this file?
                String path = ois.readUTF();
                String hash = ois.readUTF();

                // Does the file exist on the client && does the hash match
                if (clientFiles.containsKey(path) && hash.equals(clientFiles.get(path))) {
                    // Client: Yes I do!
                    clientFilesCopy.remove(path);
                    oos.writeInt(EBinaryAnswer.YES.getValue());
                    oos.flush();
                } else {
                    didSyncFiles = true;
                    // Client: No I don't!
                    oos.writeInt(EBinaryAnswer.NO.getValue());
                    oos.flush();

                    // Server: Here is the file.
                    long fileSize = ois.readLong();
                    if (updateFile(path, fileSize)) {
                        clientFilesCopy.remove(path);
                    } else {
                        Logger.error(String.format("Failed to update file: %s", path));
                        clientFilesCopy.replace(path, "retry");
                    }
                }
                guiUpdate.f();
            }

            if (!didSyncFiles) {
                Logger.log("All files match the server.");
            }

            // Return list of remaining files to deal with
            return clientFilesCopy
                .entrySet()
                .stream()
                .map(entry -> {
                    if ("retry".equals(entry.getValue())) {
                        return entry;
                    }
                    entry.setValue("delete");
                    return entry;
                })
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        } catch( IOException e) {
            Logger.debug(e);
        }
        return null;
    }

    private boolean updateFile(String path, long size) {
        FileProgress GUIUpdater = new FileProgress();

        Path clientFile = Paths.get(path);
        try {
            Files.createDirectories(clientFile.getParent());
        } catch (IOException e) {
            Logger.debug("Could not create parent directories for: " + clientFile.toString());
            Logger.debug(e);
        }

        if (Files.exists(clientFile)) {
            try {
                Files.delete(clientFile);
                Files.createFile(clientFile);
            } catch (IOException e) {
                Logger.debug("Failed to delete file: " + clientFile.getFileName().toString());
                Logger.debug(e);
            }
        }

        try {
            Logger.debug("Attempting to write file (" + clientFile.toString() + ")");
            OutputStream wr = Files.newOutputStream(clientFile);

            byte[] outBuffer = new byte[clientSocket.getReceiveBufferSize()];

            int bytesReceived;
            long totalBytesReceived = 0L;

            while ((bytesReceived = ois.read(outBuffer)) > 0) {
                totalBytesReceived += bytesReceived;

                wr.write(outBuffer, 0, bytesReceived);
                GUIUpdater.updateProgress((int) Math.ceil(totalBytesReceived / size * 100), clientFile.getFileName().toString());

                if (totalBytesReceived == size) {
                    break;
                }
            }

            // TODO test out empty files
            //                Logger.debug("Empty file: " + clientFile.getFileName());
            wr.flush();
            wr.close();

            GUIUpdater.fileFinished();
            Logger.debug("Finished writing file" + clientFile.toString());
        } catch (FileNotFoundException e) {
            Logger.debug("Failed to create file (" + clientFile.toString() + "): " + e.getMessage());
            Logger.debug(e);
            return false;
        } catch (IOException e) {
            Logger.debug(e);
            return false;
        }

        Logger.log(ServerSync.strings.getString("update_success") + ": " + clientFile.toString());
        return true;
    }

    /**
     * Terminates the listener thread on the server for this client
     */
    private void exit() {
        if (SCOMS == null) {
            // NO server messages set up, server must have not connected at this point
            return;
        }
        String message = SCOMS.get(EServerMessage.EXIT);
        Logger.debug(ServerSync.strings.getString("debug_server_exit"));

        try {
            oos.writeObject(message);
            oos.flush();
        } catch (IOException e) {
            Logger.debug("Failed to write object (" + message + ") to client output stream");
        }
    }

    /**
     * Releases resources related to this server instance, MUST call this when
     * interaction is finished if a server is opened
     *
     * @return true if client successfully closes all connections
     */
    public boolean close() {
        exit();
        Logger.debug(ServerSync.strings.getString("debug_server_close"));
        try {
            if (clientSocket != null && !clientSocket.isClosed())
                clientSocket.close();
        } catch (IOException e) {
            Logger.debug("Failed to close client socket: " + e.getMessage());
            return false;
        }
        Logger.debug(ServerSync.strings.getString("debug_server_close_success"));
        return true;
    }
}
