package storage;

import java.io.*;
import java.net.*;
import java.util.Arrays;

import common.*;
import rmi.*;
import naming.*;

/** Storage server.

    <p>
    Storage servers respond to client file access requests. The files accessible
    through a storage server are those accessible under a given directory of the
    local filesystem.
 */
public class StorageServer implements Storage, Command
{
	clSkeleton<Storage> clientSkeleton;
	cmSkeleton<Command> commandSkeleton;
	boolean clientStopped = false;
	boolean commandStopped = false;
	File root;
	private class clSkeleton<Storage> extends Skeleton<Storage>
	{
		
		public clSkeleton(Class<Storage> arg0, Storage arg1) {
			super(arg0, arg1);
		}
		
		public clSkeleton(Class<Storage> arg0, Storage arg1,
				InetSocketAddress arg2) {
			super(arg0, arg1, arg2);
		}

		@Override
		protected void stopped(Throwable e)
		{
			synchronized(clSkeleton.this)
			{
				clientStopped = true;
				clSkeleton.this.notifyAll();
			}
		}
	}
	private class cmSkeleton<Command> extends Skeleton<Command>
	{
		

		public cmSkeleton(Class<Command> arg0, Command arg1) {
			super(arg0, arg1);
		}
		
		public cmSkeleton(Class<Command> arg0, Command arg1,
				InetSocketAddress arg2) {
			super(arg0, arg1, arg2);
		}

		@Override
		protected void stopped(Throwable e)
		{
			synchronized(cmSkeleton.this)
			{
				commandStopped = true;
				cmSkeleton.this.notifyAll();
			}
		}
	}
    /** Creates a storage server, given a directory on the local filesystem, and
        ports to use for the client and command interfaces.

        <p>
        The ports may have to be specified if the storage server is running
        behind a firewall, and specific ports are open.

        @param root Directory on the local filesystem. The contents of this
                    directory will be accessible through the storage server.
                    MAY NOT BE ABSOLUTE.
        @param client_port Port to use for the client interface, or zero if the
                           system should decide the port.
        @param command_port Port to use for the command interface, or zero if
                            the system should decide the port.
        @throws NullPointerException If <code>root</code> is <code>null</code>.
    */
    public StorageServer(File root, int client_port, int command_port)
    {
        if(root == null) {
        	throw new NullPointerException();
        }
        
        if(client_port == 0) {
        	clientSkeleton = new clSkeleton<Storage>(Storage.class, this);
        }
        else {
        	clientSkeleton = new clSkeleton<Storage>(Storage.class, this, new InetSocketAddress(client_port));
        }
        
        if(command_port == 0) {
        	commandSkeleton = new cmSkeleton<Command>(Command.class, this);
        }
        else {
        	commandSkeleton = new cmSkeleton<Command>(Command.class, this, new InetSocketAddress(command_port));
        }
        
        this.root = root;
    }

    /** Creates a storage server, given a directory on the local filesystem.

        <p>
        This constructor is equivalent to
        <code>StorageServer(root, 0, 0)</code>. The system picks the ports on
        which the interfaces are made available.

        @param root Directory on the local filesystem. The contents of this
                    directory will be accessible through the storage server.
                    MAY NOT BE ABSOLUTE.
        @throws NullPointerException If <code>root</code> is <code>null</code>.
     */
    public StorageServer(File root)
    {
        this(root, 0, 0);
    }

    /** Starts the storage server and registers it with the given naming
        server.

        @param hostname The externally-routable hostname of the local host on
                        which the storage server is running. This is used to
                        ensure that the stub which is provided to the naming
                        server by the <code>start</code> method carries the
                        externally visible hostname or address of this storage
                        server.
        @param naming_server Remote interface for the naming server with which
                             the storage server is to register.
        @throws UnknownHostException If a stub cannot be created for the storage
                                     server because a valid address has not been
                                     assigned.
        @throws FileNotFoundException If the directory with which the server was
                                      created does not exist or is in fact a
                                      file.
        @throws RMIException If the storage server cannot be started, or if it
                             cannot be registered.
     */
    public synchronized void start(String hostname, Registration naming_server)
        throws RMIException, UnknownHostException, FileNotFoundException
    {
        if(!root.exists() || !root.isDirectory()) {
        	throw new FileNotFoundException();
        }
        
        clientSkeleton.start();
        commandSkeleton.start();
        
        Path[] dupeFiles = naming_server.register(Stub.create(Storage.class, clientSkeleton, hostname), Stub.create(Command.class, commandSkeleton, hostname), Path.list(root));
        
        for(int i=0; i<dupeFiles.length; i++) {
        	delete(dupeFiles[i]);
        }
        
        deleteEmptyDirs(root);
    }

    private synchronized void deleteEmptyDirs(File r) {
    	if(!r.isDirectory()) {
    		return;
    	}
    	
    	if(r.list().length > 0) {
    		for(File f : r.listFiles()) {
    			deleteEmptyDirs(f);
    		}
    	}
    	
    	if(r.list().length == 0) {
    		r.delete();
    	}
    }
    
    /** Stops the storage server.

        <p>
        The server should not be restarted.
     */
    public void stop()
    {
        // TODO: Is this correct?
    	
    	
    	clientSkeleton.stop();
        commandSkeleton.stop();
        if(clientStopped && commandStopped)
            stopped(null);
        
    	
    }

    /** Called when the storage server has shut down.

        @param cause The cause for the shutdown, if any, or <code>null</code> if
                     the server was shut down by the user's request.
     */
    protected void stopped(Throwable cause)
    {
    }

    // The following methods are documented in Storage.java.
    @Override
    public synchronized long size(Path file) throws FileNotFoundException
    {
    	File temp = file.toFile(root);
        if(!temp.exists() || temp.isDirectory())
        	throw new FileNotFoundException("The given file does not exist or is a directory.");
        return temp.length();
    }

    @Override
    public synchronized byte[] read(Path file, long offset, int length)
        throws FileNotFoundException, IOException
    {
        File f = file.toFile(root);

        if(!f.canRead() || f.isDirectory()) {
        	throw new FileNotFoundException();
        }
        
    	if(offset < 0 || offset > Integer.MAX_VALUE || length < 0 || offset + length > f.length())
    		throw new IndexOutOfBoundsException();
        
      
        RandomAccessFile reader = new RandomAccessFile(f, "r");
        
        reader.seek(offset);
        
        byte[] bbuf = new byte[length]; 
        reader.readFully(bbuf);
        
        return bbuf;
    }

    @Override
    public synchronized void write(Path file, long offset, byte[] data)
        throws FileNotFoundException, IOException
    {
    	if(offset < 0)
    		throw new IndexOutOfBoundsException();
        
    	File temp = file.toFile(root);
        
        if(!temp.exists() || temp.isDirectory())
        	throw new FileNotFoundException("The given file does not exist or is a directory.");
        if(!temp.canWrite())
        	throw new IOException("The file is not writable.");
        
        RandomAccessFile fout = new RandomAccessFile(temp, "rw");
        fout.seek(offset);
        try {
        	fout.write(data);
        } catch(IOException e)
        {
        	throw new IOException("Threw " + e + " when writing to file.");
        }
    }

    // The following methods are documented in Command.java.
    @Override
    public synchronized boolean create(Path file)
    {
        if(file.isRoot()) {
        	return false;
        }
        
        File parent = file.parent().toFile(root);
        
        parent.mkdirs();
        
        File f = file.toFile(root);
        

        try {
        	return f.createNewFile();
		} catch (IOException e) {
			return false;
		}
    }

    @Override
    public synchronized boolean delete(Path path)
    {
        if(path.isRoot()) {
        	return false;
        }
        
        return deleteHelper(path.toFile(root));
    }
    
    private boolean deleteHelper(File f) {
		if (f.isDirectory()) {
			for (File c : f.listFiles())
				deleteHelper(c);
		}
		return f.delete();
    }

    @Override
    public synchronized boolean copy(Path file, Storage server)
        throws RMIException, FileNotFoundException, IOException
    {
		// Handle files larger than heap memory
        if(file == null || server == null)
        	throw new NullPointerException("The file or server given was null.");
        
       	server.read(file, 0, 1);
        
        if(file.toFile(root).exists()) {
        	delete(file);
        }

        if(!create(file)) {
        	throw new IOException("File failed to be created");
        }
        
        long filesize = server.size(file);
        
        int buffsize = 8192; // 8KB buffer
        
        long offset = buffsize;
        byte[] buff = new byte[buffsize];
        
        for(long i=0; i*offset<=filesize; i++) {
        	if(i*offset+buffsize > filesize) {
        		buff = server.read(file, i*offset, (int) (filesize - i*offset));
        		write(file, i*offset, Arrays.copyOfRange(buff, 0, (int) (filesize - i*offset)));
        	} else {
        		buff = server.read(file, i*offset, buff.length);
        		write(file, i*offset, buff);
        	}
        }
        return true;
    }
    
    
}
