package com.superzanti.serversync.client;

import com.superzanti.serversync.ServerSync;
import com.superzanti.serversync.filemanager.FileManager;
import com.superzanti.serversync.server.Server;
import com.superzanti.serversync.util.GlobPathMatcher;
import com.superzanti.serversync.util.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * The sync process for clients.
 * - Query for file differences
 * - Update different files
 * - Delete files that are not present on the server
 * <p>
 * Caveats:
 * - Client can configure to ignore files from deletion (e.g. Optifine, NEET and other such client side mods)
 * <p>
 * Responsibility:
 * The client is responsible for fetching the state it is meant to be in and either pulling what it needs from the
 * server or removing excess.
 *
 * @author Rheimus
 */
public class ClientWorker implements Runnable {

    private boolean errorInUpdates = false;
    private boolean updateHappened = false;

    private Server server;
    private List<String> managedDirectories = new ArrayList<>(0);

    private FileManager fileManager = new FileManager();

    @Override
    public void run() {
        updateHappened = false;

        ServerSync.clientGUI.disableSyncButton();
        Logger.getLog().clearUserFacingLog();

        server = new Server(this, ServerSync.CONFIG.SERVER_IP, ServerSync.CONFIG.SERVER_PORT);

        if (!server.connect()) {
            errorInUpdates = true;
            closeWorker();
            return;
        }

        try {
            managedDirectories = getServerManagedDirectories();

            // UPDATE
            managedDirectories.forEach(path -> {
                try {
                    Files.createDirectories(Paths.get(path));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
            Map<String, String> remainingFiles = updateFiles(getClientState());

            // DELETE
            deleteFiles(remainingFiles);

            // UNEXPECTED FAILURES
            if (remainingFiles.containsValue("retry")) {
                Logger.log(remainingFiles.toString());
                Logger.log("Some files failed to sync, retrying once more");
                remainingFiles = updateFiles(getClientState());

                if (remainingFiles.containsValue("retry")) {
                    Logger.log(remainingFiles.toString());
                    Logger.error("Some files failed to sync on second pass, something is very broken :(");
                    errorInUpdates = true;
                }
            }

            // CLEANUP
            FileManager.removeEmptyDirectories(
                managedDirectories.stream().map(Paths::get).collect(Collectors.toList()),
                (dir) -> {
                    Logger.log(String.format("<C> Removed empty directory: %s", dir.toString()));
                }
            );
        } catch (IOException e) {
            Logger.debug(e);
        }

        updateHappened = true;
        closeWorker();
        Logger.log(ServerSync.strings.getString("update_complete"));
    }

    private void closeWorker() {
        if (server == null) {
            return;
        }

        if (server.close()) {
            Logger.debug("Successfully closed all connections");
        }

        if (!updateHappened && !errorInUpdates) {
            Logger.log(ServerSync.strings.getString("update_not_needed"));
            ServerSync.clientGUI.updateProgress(100);
        } else {
            Logger.debug(ServerSync.strings.getString("update_happened"));
            ServerSync.clientGUI.updateProgress(100);
        }

        if (errorInUpdates) {
            Logger.error(ServerSync.strings.getString("update_error"));
        }

        ServerSync.clientGUI.enableSyncButton();
    }

    private List<String> getServerManagedDirectories() {
        return server.fetchManagedDirectories();
    }

    /**
     * The sate of the client from the perspective of what the server wants to manage.
     * e.g. If the server wants to manage 'mods', 'config' and 'my-cool-extras' then this will return the content
     * of the clients 'mods', 'config' and 'my-cool-extras' directories.
     *
     * @throws IOException when files on the client could not be accessed.
     */
    private Map<String, String> getClientState() throws IOException {
        return fileManager.getDiffableFilesFromDirectories(managedDirectories);
    }

    private Map<String, String> updateFiles(Map<String, String> clientFiles) {
        Logger.log("<------> " + ServerSync.strings.getString("update_start") + " <------>");
        Logger.debug(ServerSync.strings.getString("ignoring") + " " + ServerSync.CONFIG.FILE_IGNORE_LIST);

        // Progress tracking setup
        AtomicInteger currentProgress = new AtomicInteger();
        int maxProgress = server.fetchNumberOfServerManagedFiles();
        if (maxProgress == 0) {
            Logger.log("Server has no files to sync?");
            return new HashMap<>(0);
        }
        if (maxProgress == -1) {
            Logger.debug("Failed to get the number of files managed by the server");
        }
        //----

        // Update files if needed, return files that remain after testing against the servers state
        // these will be the files the the client contains but the server does not.
        return server.syncFiles(
            clientFiles,
            () -> {
                ServerSync.clientGUI.updateProgress(
                    (int) (currentProgress.incrementAndGet() / maxProgress)
                );
            }
        );
    }

    private void deleteFile(String path) {
        Path file = Paths.get(path);

        if (GlobPathMatcher.matches(file, ServerSync.CONFIG.FILE_IGNORE_LIST)) {
            Logger.log(String.format("<I> %s %s", ServerSync.strings.getString("ignoring"), path));
            return;
        }

        try {
            if (Files.deleteIfExists(file)) {
                Logger.log(String.format("<D> %s %s", path, ServerSync.strings.getString("delete_success")));
            } else {
                Logger.log("!!! failed to delete: " + path + " !!!");
            }
        } catch (IOException e) {
            Logger.debug(e);
        }
    }

    private void deleteFiles(Map<String, String> files) {
        Logger.log("<------> " + ServerSync.strings.getString("delete_start") + " <------>");
        if (files.size() == 0) {
            Logger.log("No files to delete.");
            return;
        }

        Logger.log(String.format("Ignore patterns: %s", String.join(", ", ServerSync.CONFIG.FILE_IGNORE_LIST)));
        files
            .entrySet()
            .stream()
            .filter(e -> "delete".equals(e.getValue()))
            .forEach(e -> deleteFile(e.getKey()));

        Logger.debug(files.toString());
    }
}
