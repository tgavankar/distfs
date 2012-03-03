package naming;

import java.io.*;
import java.net.*;
import java.util.*;

import rmi.*;
import common.*;
import storage.*;

/** Naming server.

    <p>
    Each instance of the filesystem is centered on a single naming server. The
    naming server maintains the filesystem directory tree. It does not store any
    file data - this is done by separate storage servers. The primary purpose of
    the naming server is to map each file name (path) to the storage server
    which hosts the file's contents.

    <p>
    The naming server provides two interfaces, <code>Service</code> and
    <code>Registration</code>, which are accessible through RMI. Storage servers
    use the <code>Registration</code> interface to inform the naming server of
    their existence. Clients use the <code>Service</code> interface to perform
    most filesystem operations. The documentation accompanying these interfaces
    provides details on the methods supported.

    <p>
    Stubs for accessing the naming server must typically be created by directly
    specifying the remote network address. To make this possible, the client and
    registration interfaces are available at well-known ports defined in
    <code>NamingStubs</code>.
 */
public class NamingServer implements Service, Registration
{
    FsNode fsRoot;
    Skeleton<Service> clientSkeleton;
    Skeleton<Registration> regisSkeleton;
	
    
    private class FsNode {
    	HashMap<String, FsNode> children;
    	String name;
    	boolean isFile;
    	private Storage s;
		private Command c;
    	
    	public FsNode(String n) {
    		// Node for directory
    		children = new HashMap<String, FsNode>();
    		name = n;
    		isFile = false;
    		s = null;
    		c = null;
    	}
    	
    	public FsNode(String n, Storage s, Command c) {
    		// Node for file
    		children = null;
    		name = n;
    		isFile = true;
    		this.s = s;
    		this.c = c;
    	}
    	
    	public String getName() {
    		return name;
    	}
    	
    	public FsNode getChild(String name) {
    		return children.get(name);
    	}
    	
    	public HashMap<String, FsNode> getChildren() {
    		return children;
    	}
    	
    	public void addChild(String name, FsNode child) {
    		children.put(name, child);
    	}
    	
    	public boolean isFile() {
    		return isFile;
    	}
    	
    	public Storage getStorage() {
			return s;
		}
		
		public Command getCommand() {
			return c;
		}
    }
    
    /** Creates the naming server object.

        <p>
        The naming server is not started.
     */
    public NamingServer()
    {
        fsRoot = new FsNode("");
    	clientSkeleton = new Skeleton<Service>(Service.class, this);
    	regisSkeleton = new Skeleton<Registration>(Registration.class, this);

    }

    /** Starts the naming server.

        <p>
        After this method is called, it is possible to access the client and
        registration interfaces of the naming server remotely.

        @throws RMIException If either of the two skeletons, for the client or
                             registration server interfaces, could not be
                             started. The user should not attempt to start the
                             server again if an exception occurs.
     */
    public synchronized void start() throws RMIException
    {
    	clientSkeleton.start();
    	regisSkeleton.start();
        
    }

    /** Stops the naming server.

        <p>
        This method commands both the client and registration interface
        skeletons to stop. It attempts to interrupt as many of the threads that
        are executing naming server code as possible. After this method is
        called, the naming server is no longer accessible remotely. The naming
        server should not be restarted.
     */
    public void stop()
    {
        throw new UnsupportedOperationException("not implemented");
    }

    /** Indicates that the server has completely shut down.

        <p>
        This method should be overridden for error reporting and application
        exit purposes. The default implementation does nothing.

        @param cause The cause for the shutdown, or <code>null</code> if the
                     shutdown was by explicit user request.
     */
    protected void stopped(Throwable cause)
    {
    }

    // The following public methods are documented in Service.java.
    /*@Override
    public void lock(Path path, boolean exclusive) throws FileNotFoundException
    {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void unlock(Path path, boolean exclusive)
    {
        throw new UnsupportedOperationException("not implemented");
    }*/

    @Override
    public boolean isDirectory(Path path) throws FileNotFoundException
    {
		FsNode current = fsRoot;
        
        for(String p : path) {
        	current = current.getChild(p);
        	if(current == null) {
        		throw new FileNotFoundException();
        	}
        }
    	
        return !current.isFile();
    }

    @Override
    public String[] list(Path directory) throws FileNotFoundException
    {
        if(!isDirectory(directory)) {
        	throw new FileNotFoundException();
        }
        
        FsNode current = fsRoot;
        
        for(String p : directory) {
        	current = current.getChild(p);
        }
        
        return (String[]) current.getChildren().keySet().toArray();
    }

    @Override
    public boolean createFile(Path file)
        throws RMIException, FileNotFoundException
    {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public boolean createDirectory(Path directory) throws FileNotFoundException
    {
    	// Should this be an RMI call to do it serverside too?
    	isDirectory(directory.parent());  // We want the throwing of FileNotFoundException if it's not
    	
    	FsNode parent = fsRoot;
    	FsNode current;
        for(String p : directory) {
        	current = parent.getChild(p);
        	if(current == null) {
    			parent.addChild(p, new FsNode(p));
    			return true;

        	}
        	if(current.getName().equals(directory.last())) {
        		return false;
        	}
        	parent = current;
        }
        return false;
    }

    @Override
    public boolean delete(Path path) throws FileNotFoundException
    {
    	throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Storage getStorage(Path file) throws FileNotFoundException
    {
        FsNode current = fsRoot;
        
        for(String p : file) {
        	current = current.getChild(p);
        	if(current == null) {
        		throw new FileNotFoundException();
        	}
        }
    	
        if(!current.isFile()) {
        	throw new FileNotFoundException();
        }
        
    	return current.getStorage();
    }

    // The method register is documented in Registration.java.
    @Override
    public Path[] register(Storage client_stub, Command command_stub,
                           Path[] files)
    {
		ArrayList<Path> dupeFiles = new ArrayList<Path>();
        
		for(int i=0; i<files.length; i++) {
			FsNode current = fsRoot;
			boolean isDupe = false;
	        for(String p : files[i]) {
	        	current = current.getChild(p);
	        	if(current == null) {
	        		break;
	        	}
	        	if(current.isFile()) {
	        		isDupe = true;
	        	}
	        }
	        
        	if(isDupe) {
        		// Dupe found, add to return list and go to next one
        		dupeFiles.add(files[i]);
        		continue;
        	}
        	
        	// New file, so add to file tree
        	FsNode parent = fsRoot;
	        for(String p : files[i]) {
	        	current = parent.getChild(p);
	        	if(current == null) {
	        		FsNode newNode;
	        		if(p.equals(files[i].last())) {
	        			newNode = new FsNode(p, client_stub, command_stub);
	        		}
	        		else {
	        			newNode = new FsNode(p);
	        		}
	        		parent.addChild(p, newNode);
	        	}
	        	parent = current;
	        }
        }
        
    	return (Path[]) dupeFiles.toArray();
    }
}
