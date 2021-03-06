package io.s4.ft;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.commons.codec.binary.Base64;
import org.apache.log4j.Logger;

public class DefaultFileSystemStateStorage 
extends Thread
implements StateStorage {

    private static Logger logger = Logger
            .getLogger(DefaultFileSystemStateStorage.class);
    private String storageRootPath;
    private ArrayBlockingQueue<ToSave> queue= new ArrayBlockingQueue<ToSave>(1000);
    private boolean shutdown = false;
    
    class ToSave {
        SafeKeeperId key;
        byte[] state;
        StorageCallback cb;
        
        ToSave(SafeKeeperId key, byte[] state,
            StorageCallback callback) {
            this.key = key;
            this.state = state;
            this.cb = callback;
        }
        
    }
    @Override
    public void saveState(SafeKeeperId key, byte[] state,
            StorageCallback callback) {
        ToSave toSave = new ToSave(key, state, callback);
        queue.add(toSave);
    }
    
    public void run(){
        while(!shutdown){
            ToSave toSave = null;
            
            try{
                toSave = queue.poll(1000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                LOG.warn("Interrupted while waiting on incoming queue.", e);
            }
            
            if(toSave == null){
                continue;
            }
        
            File f = safeKeeperID2File(toSave.key);
            if (!f.exists()) {
                if (!f.getParentFile().exists()) {
                    // parent file has prototype id
                    if (!f.getParentFile().mkdir()) {
                        toSave.cb.storageOperationResult(
                            SafeKeeper.StorageResultCode.FAILURE,
                            "Cannot create directory for storing PE for prototype: "
                                    + f.getParentFile().getAbsolutePath());
                    return;
                }
            }
            // TODO handle IO exception
            try {
                f.createNewFile();
            } catch (IOException e) {
                toSave.cb.storageOperationResult(
                        SafeKeeper.StorageResultCode.FAILURE, e.getMessage());
                return;
            }
        } else {
            if (!f.delete()) {
                callback.storageOperationResult(
                        SafeKeeper.StorageResultCode.FAILURE,
                        "Cannot delete previously saved checkpoint file ["
                                + f.getParentFile().getAbsolutePath() + "]");
                return;
            }
        }
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f);
            fos.write(toSave.state);
        } catch (FileNotFoundException e) {
            toSave.cb.storageOperationResult(
                    SafeKeeper.StorageResultCode.FAILURE, e.getMessage());
        } catch (IOException e) {
            toSave.cb.storageOperationResult(
                    SafeKeeper.StorageResultCode.FAILURE, e.getMessage());
        } finally {
            try {
                if (fos != null) {
                    fos.close();
                }
            } catch (IOException e) {
                logger.error(e);
            }
        }
        }
    }

    @Override
    public byte[] fetchState(SafeKeeperId key) {
        File file = safeKeeperID2File(key);
        if (logger.isDebugEnabled()) {
            logger.debug("Fetching " + file.getAbsolutePath() + "for : " + key);
        }
        if (file != null && file.exists()) {

            // TODO use commons-io or guava
            FileInputStream in = null;
            try {
                in = new FileInputStream(file);

                long length = file.length();

                /*
                 * Arrays can only be created using int types, so ensure that
                 * the file size is not too big before we downcast to create the
                 * array.
                 */
                if (length > Integer.MAX_VALUE) {
                    throw new IOException("Error file is too large: "
                            + file.getName() + " " + length + " bytes");
                }

                byte[] buffer = new byte[(int) length];
                int offSet = 0;
                int numRead = 0;

                while (offSet < buffer.length
                        && (numRead = in.read(buffer, offSet, buffer.length
                                - offSet)) >= 0) {
                    offSet += numRead;
                }

                if (offSet < buffer.length) {
                    throw new IOException("Error, could not read entire file: "
                            + file.getName() + " " + offSet + "/"
                            + buffer.length + " bytes read");
                }

                in.close();
                return buffer;
            } catch (FileNotFoundException e1) {
                logger.error(e1);
            } catch (IOException e2) {
                logger.error(e2);
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Exception e) {
                        logger.warn(e);
                    }
                }
            }
        }
        return null;

    }

    @Override
    public Set<SafeKeeperId> fetchStoredKeys() {
        Set<SafeKeeperId> keys = new HashSet<SafeKeeperId>();
        File rootDir = new File(storageRootPath);
        File[] dirs = rootDir.listFiles(new FileFilter() {
            @Override
            public boolean accept(File file) {
                return file.isDirectory();
            }
        });
        for (File dir : dirs) {
            File[] files = dir.listFiles(new FileFilter() {
                @Override
                public boolean accept(File file) {
                    return (file.isFile());
                }
            });
            for (File file : files) {
                keys.add(file2SafeKeeperID(file));
            }
        }
        return keys;
    }

    // files kept as : root/<partitionId>/<prototypeId>/encodedKeyWithFullInfo
    private File safeKeeperID2File(SafeKeeperId key) {

        return new File(storageRootPath
                + File.separator
                + key.getPrototypeId()
                + File.separator
                + Base64.encodeBase64URLSafeString(key.getStringRepresentation()
                        .getBytes()));
    }

    private SafeKeeperId file2SafeKeeperID(File file) {
        SafeKeeperId id = null;
        id = new SafeKeeperId(new String(Base64.decodeBase64(file.getName())));
        return id;
    }

    public String getStorageRootPath() {
        return storageRootPath;
    }

    public void setStorageRootPath(String storageRootPath) {
        this.storageRootPath = storageRootPath;
        File rootPathFile = new File(storageRootPath);
        if (!rootPathFile.exists()) {
            if (!rootPathFile.mkdirs()) {
                logger.error("could not create root storage directory : "
                        + storageRootPath);
            }

        }
    }

    public void checkStorageDir() {
        if (storageRootPath == null) {

            File defaultStorageDir = new File(System.getProperty("user.dir")
                    + File.separator + "tmp" + File.separator + "storage");
            storageRootPath = defaultStorageDir.getAbsolutePath();
            if (logger.isInfoEnabled()) {
                logger.info("Unspecified storage dir; using default dir: "
                        + defaultStorageDir.getAbsolutePath());
            }
            if (!defaultStorageDir.exists()) {
                if (!(defaultStorageDir.mkdirs())) {
                    logger.error("Storage directory not specified, and cannot create default storage directory : "
                            + defaultStorageDir.getAbsolutePath());
                    // TODO exit?
                    System.exit(-1);
                }
            }
        }
    }
    
    public boolean shutdown(){
        boolean current = shutdown;
        shutdown = true;
        return current;
    }

}
