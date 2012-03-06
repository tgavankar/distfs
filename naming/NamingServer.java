package naming;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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
	private FsNode fsRoot;
    private Skeleton<Service> clientSkeleton;
    private Skeleton<Registration> regisSkeleton;
	private HashMap<Command, Storage> storageList;
	private ReadWriteLock lock;
	
	boolean clientStopped = false;
	boolean regStopped = false;
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
	private class regSkeleton<Registration> extends Skeleton<Registration>
	{
		

		public regSkeleton(Class<Registration> arg0, Registration arg1) {
			super(arg0, arg1);
		}
		
		public regSkeleton(Class<Registration> arg0, Registration arg1,
				InetSocketAddress arg2) {
			super(arg0, arg1, arg2);
		}

		@Override
		protected void stopped(Throwable e)
		{
			synchronized(regSkeleton.this)
			{
				regStopped = true;
				regSkeleton.this.notifyAll();
			}
		}
	}

    
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
    	clientSkeleton = new clSkeleton<Service>(Service.class, this, new InetSocketAddress(NamingStubs.SERVICE_PORT));
    	regisSkeleton = new regSkeleton<Registration>(Registration.class, this, new InetSocketAddress(NamingStubs.REGISTRATION_PORT));
    	storageList = new HashMap<Command, Storage>();
    	lock = new ReadWriteLock();
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
    	clientSkeleton.stop();
        regisSkeleton.stop();
        if(clientStopped && regStopped)
            stopped(null);
        
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

    public class ReadWriteLock{

    	  private Map<Thread, Integer> readingThreads =
    	       new HashMap<Thread, Integer>();

    	   private int writeAccesses    = 0;
    	   private int writeRequests    = 0;
    	   private Thread writingThread = null;


    	  public synchronized void lockRead() throws InterruptedException{
    	    Thread callingThread = Thread.currentThread();
    	    while(! canGrantReadAccess(callingThread)){
    	      wait();
    	    }

    	    readingThreads.put(callingThread,
    	     (getReadAccessCount(callingThread) + 1));
    	  }

    	  private boolean canGrantReadAccess(Thread callingThread){
    	    if( isWriter(callingThread) ) return true;
    	    if( hasWriter()             ) return false;
    	    if( isReader(callingThread) ) return true;
    	    if( hasWriteRequests()      ) return false;
    	    return true;
    	  }


    	  public synchronized void unlockRead(){
    	    Thread callingThread = Thread.currentThread();
    	    if(!isReader(callingThread)){
    	      throw new IllegalMonitorStateException("Calling Thread does not" +
    	        " hold a read lock on this ReadWriteLock");
    	    }
    	    int accessCount = getReadAccessCount(callingThread);
    	    if(accessCount == 1){ readingThreads.remove(callingThread); }
    	    else { readingThreads.put(callingThread, (accessCount -1)); }
    	    notifyAll();
    	  }

    	  public synchronized void lockWrite() throws InterruptedException{
    	    writeRequests++;
    	    Thread callingThread = Thread.currentThread();
    	    while(! canGrantWriteAccess(callingThread)){
    	      wait();
    	    }
    	    writeRequests--;
    	    writeAccesses++;
    	    writingThread = callingThread;
    	  }

    	  public synchronized void unlockWrite() throws InterruptedException{
    	    if(!isWriter(Thread.currentThread())){
    	      throw new IllegalMonitorStateException("Calling Thread does not" +
    	        " hold the write lock on this ReadWriteLock");
    	    }
    	    writeAccesses--;
    	    if(writeAccesses == 0){
    	      writingThread = null;
    	    }
    	    notifyAll();
    	  }

    	  private boolean canGrantWriteAccess(Thread callingThread){
    	    if(isOnlyReader(callingThread))    return true;
    	    if(hasReaders())                   return false;
    	    if(writingThread == null)          return true;
    	    if(!isWriter(callingThread))       return false;
    	    return true;
    	  }


    	  private int getReadAccessCount(Thread callingThread){
    	    Integer accessCount = readingThreads.get(callingThread);
    	    if(accessCount == null) return 0;
    	    return accessCount.intValue();
    	  }


    	  private boolean hasReaders(){
    	    return readingThreads.size() > 0;
    	  }

    	  private boolean isReader(Thread callingThread){
    	    return readingThreads.get(callingThread) != null;
    	  }

    	  private boolean isOnlyReader(Thread callingThread){
    	    return readingThreads.size() == 1 &&
    	           readingThreads.get(callingThread) != null;
    	  }

    	  private boolean hasWriter(){
    	    return writingThread != null;
    	  }

    	  private boolean isWriter(Thread callingThread){
    	    return writingThread == callingThread;
    	  }

    	  private boolean hasWriteRequests(){
    	      return this.writeRequests > 0;
    	  }

    	}
    
    // The following public methods are documented in Service.java.
    @Override
    public void lock(Path path, boolean exclusive) throws FileNotFoundException
    {
        if(path == null)
        	throw new NullPointerException("The path given was null.");
        if(!path.toFile(null).exists())
        	throw new FileNotFoundException("The path does not point to a file.");
        

        if(exclusive) {
        	try {
				lock.lockWrite();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block

			}
        }
        else {
        	try {
				lock.lockRead();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block

			}
        }

    }

    @Override
    public void unlock(Path path, boolean exclusive)
    {
    	if(path == null)
        	throw new NullPointerException("The path given was null.");
    	if(!path.toFile(null).exists())
         	throw new IllegalArgumentException("The path does not point to a file.");
    	
        if(exclusive) {
        	try {
				lock.unlockWrite();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
			}
        }
        else {
				lock.unlockRead();
        }

    }

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
        
        Set<String> filenames = current.getChildren().keySet();
        
        return filenames.toArray(new String[filenames.size()]);
    }

    @Override
    public boolean createFile(Path file)
        throws RMIException, FileNotFoundException
    {
    	if(file == null) {
    		throw new NullPointerException();
    	}
    	
    	if(file.isRoot()) {
    		return false;
    	}
    	
    	if(!isDirectory(file.parent())) {
    		throw new FileNotFoundException();
    	}
    	
    	FsNode parent = fsRoot;
    	FsNode current;
        for(String p : file) {
        	current = parent.getChild(p);
        	
        	if(current == null) { 
        		Command cs = getRandomServer();
				cs.create(file);
				parent.addChild(p, new FsNode(p, storageList.get(cs), cs));
				return true;
        	}
        	if(current.isFile()) {
        		return false;
        	}
        	if(current.getName().equals(file.last())) {
        		return false;
        	}
        	
        	parent = current;
        }
        return false;
    }

    private Command getRandomServer() {
    	Set<Command> storageSet = storageList.keySet();
    	Command[] storageArray = storageSet.toArray(new Command[storageSet.size()]);
    	int item = new Random().nextInt(storageArray.length);
    	return storageArray[item];    	
    }
    
    @Override
    public boolean createDirectory(Path directory) throws FileNotFoundException
    {
    	if(directory.isRoot()) {
    		return false;
    	}
    	
    	// Should this be an RMI call to do it serverside too?
    	if(!isDirectory(directory.parent())) {  // We want the throwing of FileNotFoundException if it's not
    		throw new FileNotFoundException();
    	}
    	
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
    	if(path == null)
    		throw new NullPointerException("Path was null.");
    	Storage temp = this.getStorage(path);
    	
    	return false;
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
		if(client_stub == null || command_stub == null || files == null) {
			throw new NullPointerException();
		}
    	
		if(storageList.containsKey(command_stub)) {
			throw new IllegalStateException("Duplicate registration");
		}
		
    	ArrayList<Path> dupeFiles = new ArrayList<Path>();
        
		for(int i=0; i<files.length; i++) {
			FsNode current = fsRoot;
			boolean isDupe = false;
	        for(String p : files[i]) {
	        	current = current.getChild(p);
	        	if(current == null) {
	        		break;
	        	}
	        	if(p.equals(files[i].last())) {
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
        		parent = parent.getChild(p);
	        }
        }
        
		storageList.put(command_stub, client_stub);
		
    	return dupeFiles.toArray(new Path[dupeFiles.size()]);
    }
}
