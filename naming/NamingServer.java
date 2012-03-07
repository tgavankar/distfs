package naming;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

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
	private volatile ConcurrentHashMap<Storage, Command> storageList;
	private volatile ConcurrentHashMap<Path, ReadWriteLock> lockList;
	private volatile ConcurrentHashMap<Path, Integer> replicationCounter;
	
    
    private class FsNode {
    	ConcurrentHashMap<String, FsNode> children;
    	String name;
    	boolean isFile;
    	private ArrayList<Storage> s;
    	
    	public FsNode(String n) {
    		// Node for directory
    		children = new ConcurrentHashMap<String, FsNode>();
    		name = n;
    		isFile = false;
    		s = new ArrayList<Storage>();
    	}
    	
    	public FsNode(String n, Storage s) {
    		// Node for file
    		children = null;
    		name = n;
    		isFile = true;
    		this.s = new ArrayList<Storage>();
    		this.s.add(s);
    	}
    	
    	public String getName() {
    		return name;
    	}
    	
    	public FsNode getChild(String name) {
    		return children.get(name);
    	}
    	
    	public ConcurrentHashMap<String, FsNode> getChildren() {
    		return children;
    	}
    	
    	public void addChild(String name, FsNode child) {
    		children.put(name, child);
    	}
    	
    	public boolean isFile() {
    		return isFile;
    	}
    	
    	public Storage getStorage() {
        	int item = new Random().nextInt(s.size());
        	return s.get(item);   
		}
    	
    	public ArrayList<Storage> getAllStorage() {
    		return s;
    	}
    	
    	public void addStorage(Storage s) {
    		this.s.add(s);
    	}
    	
    	public void removeStorage(Storage s) {
    		this.s.remove(s);
    	}
    }
    
    /** Creates the naming server object.

        <p>
        The naming server is not started.
     */
    public NamingServer()
    {
        fsRoot = new FsNode("");
    	clientSkeleton = new Skeleton<Service>(Service.class, this, new InetSocketAddress(NamingStubs.SERVICE_PORT));
    	regisSkeleton = new Skeleton<Registration>(Registration.class, this, new InetSocketAddress(NamingStubs.REGISTRATION_PORT));
    	storageList = new ConcurrentHashMap<Storage, Command>();
    	lockList = new ConcurrentHashMap<Path, ReadWriteLock>();
    	replicationCounter = new ConcurrentHashMap<Path, Integer>();
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

    public class ReadWriteLock {

    	private int readAccesses = 0;

    	private int writeAccesses    = 0;
    	private int writeRequests    = 0;


    	public synchronized void lockRead() throws InterruptedException {
    		while(!canGrantReadAccess()){
    			wait();
    		}
    		readAccesses++;
    	}

    	private boolean canGrantReadAccess() {
    		if(hasWriter() || hasWriteRequests())
    			return false;
    		return true;
    	}


    	public synchronized void unlockRead(){
    		readAccesses--;
    		notifyAll();
    	}

    	public synchronized void lockWrite() throws InterruptedException{
    		writeRequests++;
    		while(!canGrantWriteAccess()){
    			wait();
    		}
    		writeRequests--;
    		writeAccesses++;
    	}

    	public synchronized void unlockWrite() throws InterruptedException{
    		writeAccesses--;
    		notifyAll();
    	}

    	private boolean canGrantWriteAccess(){
    		if(hasReaders() || hasWriter())
    			return false;
    		return true;
    	}

    	private boolean hasReaders() {
    		return this.readAccesses > 0;
    	}

    	private boolean hasWriter() {
    		return this.writeAccesses > 0;
    	}

    	private boolean hasWriteRequests() {
    		return this.writeRequests > 0;
    	}

    }
    
    private class ReplicationThread extends Thread {
    	Path path;
    	
    	ReplicationThread(Path p) {
    		path = p;
    	}
    	
    	public synchronized void run() {
			Integer origCount = replicationCounter.get(path);
    		replicationCounter.put(path, 0);
    		if(origCount < 20) {
    			return;
    		}
    		
    		try {
				lock(path, false);
			} catch (FileNotFoundException e) {
				// TODO Auto-generated catch block
				return;
			}
			
			FsNode fnode = getNode(path);
			if(fnode == null)
				return; //incase something deleted the file before we acquired lock
			
			List<Storage> storages = fnode.getAllStorage();
			
			Set<Storage> allStorages = storageList.keySet();
			
			allStorages.removeAll(storages);
			
			if(allStorages.size() > 0) {
			
		    	Storage[] storageArray = allStorages.toArray(new Storage[allStorages.size()]);
		    	int item = new Random().nextInt(storageArray.length);
		    	Storage newStorage = storageArray[item];
		    	
		    	try {
					if(storageList.get(newStorage).copy(path, fnode.getStorage())) {
						fnode.addStorage(newStorage);
	        			replicationCounter.put(path, 0);
					}
					else {
						replicationCounter.put(path, origCount);
					}
				} catch (FileNotFoundException e) {
					// TODO Auto-generated catch block
	
				} catch (RMIException e) {
					// TODO Auto-generated catch block
	
				} catch (IOException e) {
					// TODO Auto-generated catch block
	
				}
			}

			unlock(path, false);
    	}
    }
    
    private class DeletionThread extends Thread {
    	Path path;
    	
    	DeletionThread(Path p) {
    		path = p;
    	}
    	
    	public synchronized void run() {
			Integer origCount = replicationCounter.get(path);
    		replicationCounter.put(path, 0);
    		if(origCount == 0) {
    			return;
    		}
    		
    		try {
				lock(path, true);
			} catch (FileNotFoundException e) {
				// TODO Auto-generated catch block
				return;
			}
			
			FsNode fnode = getNode(path);
			if(fnode == null)
				return; //incase something deleted the file before we acquired lock
			
			ArrayList<Storage> storages = new ArrayList<Storage>();
			
			//faux deep-copy so as to not mess up FsNode's list
			for(Storage s : fnode.getAllStorage()) {
				storages.add(s);
			}
			
			if(storages.size() > 1) {
		    	
		    	Storage[] storageArray = storages.toArray(new Storage[storages.size()]);
		    	int item = new Random().nextInt(storageArray.length);
		    	Storage keepStorage = storageArray[item];

		    	storages.remove(keepStorage);


		    	
		    	for(Storage s : storages) {
					try {
						deleteFromServer(path, storageList.get(s));
					} catch (RMIException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					fnode.removeStorage(s);
				}

			}

			replicationCounter.put(path, 0);
			unlock(path, true);
    	}
    }
    
    // The following public methods are documented in Service.java.
    @Override
    public void lock(Path path, boolean exclusive) throws FileNotFoundException
    {
        if(path == null)
        	throw new NullPointerException("The path given was null.");
        

        if(!isValidPath(path)) {
        	throw new FileNotFoundException("The path is not valid.");
        }
        
        List<Path> pathList = getAllParents(path);
        
        
        for(int i=0; i<pathList.size(); i++) {
        	if(lockList.get(pathList.get(i)) == null) {
        		lockList.put(pathList.get(i), new ReadWriteLock());
        	}
        }
        
        Collections.sort(pathList);

        for(int i=0; i<pathList.size(); i++) {
        	if(exclusive && i == pathList.size()-1) {
            	try {
    				if(!isDirectory(pathList.get(i))) {
    					synchronized(NamingServer.this) {
	    					Integer readCount = replicationCounter.get(pathList.get(i));
	    					if(readCount == null) {
	    						replicationCounter.put(pathList.get(i), 1);
	    					}
        					DeletionThread d = new DeletionThread(pathList.get(i));
        					d.start();
                		}
    				}
            		lockList.get(pathList.get(i)).lockWrite();
    			} catch (InterruptedException e) {
    				// TODO Auto-generated catch block

    			}
            }
            else {
            	try {
            		lockList.get(pathList.get(i)).lockRead();
    				if(!isDirectory(pathList.get(i))) {
    					synchronized(NamingServer.this) {
	    					Integer readCount = replicationCounter.get(pathList.get(i));
	    					if(readCount == null) {
	    						replicationCounter.put(pathList.get(i), 1);
	    					}
	    					if(replicationCounter.get(pathList.get(i)) >= 20) {
	    						ReplicationThread r = new ReplicationThread(pathList.get(i));
	    						r.start();
	    					}
	        				replicationCounter.put(pathList.get(i), replicationCounter.get(pathList.get(i))+1);
                		}
    				}
    			} catch (InterruptedException e) {
    				// TODO Auto-generated catch block

    			}
            }
        }
        

    }

    @Override
    public void unlock(Path path, boolean exclusive)
    {
    	if(path == null)
        	throw new NullPointerException("The path given was null.");
        
        if(!isValidPath(path)) {
        	throw new IllegalArgumentException("The path is not valid.");
        }
   	
   	
        List<Path> pathList = getAllParents(path);
        
      
        for(int i=0; i<pathList.size(); i++) {
        	if(lockList.get(pathList.get(i)) == null) {
        		lockList.put(pathList.get(i), new ReadWriteLock());
        	}
        }
        
        
        Collections.sort(pathList);

        Collections.reverse(pathList);

        for(int i=0; i<pathList.size(); i++) {
        	if(exclusive && i == 0) {
            	try {
    				lockList.get(pathList.get(i)).unlockWrite();
    			} catch (InterruptedException e) {
    				// TODO Auto-generated catch block

    			}
            }
            else {
        		lockList.get(pathList.get(i)).unlockRead();
            }
        }

    }

    private List<Path> getAllParents(Path p) {
    	ArrayList<Path> list = new ArrayList<Path>();
    	
    	list.add(p);
    	
    	while(true) {
    		try {
				list.add(p.parent());
				p = p.parent();
    		}
    		catch(IllegalArgumentException e) {
    			break;
    		}
    	}
    	
    	return list;
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
    
    private FsNode getNode(Path path) {
		FsNode current = fsRoot;
        
        for(String p : path) {
        	current = current.getChild(p);
        	if(current == null) {
        		return null;
        	}
        }
    	
        return current;
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
        		Storage ss = getRandomServer();
				storageList.get(ss).create(file);
				parent.addChild(p, new FsNode(p, ss));
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

    private Storage getRandomServer() {
    	Collection<Storage> storageSet = storageList.keySet();
    	Storage[] storageArray = storageSet.toArray(new Storage[storageSet.size()]);
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
    	if(getNode(path) == null)
    		throw new FileNotFoundException("The path given does not lead to a file or directory.");
    	return false;
    }
    
    private boolean deleteFromServer(Path path, Command server) throws RMIException {
    	return server.delete(path);
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
    
    private Command getCommand(Path file) throws FileNotFoundException
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
        
    	return storageList.get(current.getStorage());
    }

    private boolean isValidPath(Path file)
    {
        FsNode current = fsRoot;

        for(String p : file) {
        	current = current.getChild(p);
        	if(current == null) {
        		return false;
        	}
        }
    	
      
    	return true;
    }

    
    // The method register is documented in Registration.java.
    @Override
    public Path[] register(Storage client_stub, Command command_stub,
                           Path[] files)
    {
		if(client_stub == null || command_stub == null || files == null) {
			throw new NullPointerException();
		}
    	
		synchronized(this) {
			if(storageList.containsKey(client_stub)) {
				throw new IllegalStateException("Duplicate registration");
			}
			
			storageList.put(client_stub, command_stub);
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
	        			newNode = new FsNode(p, client_stub);
	        		}
	        		else {
	        			newNode = new FsNode(p);
	        		}
	        		parent.addChild(p, newNode);
	        	}
        		parent = parent.getChild(p);
	        }
        }
        	
    	return dupeFiles.toArray(new Path[dupeFiles.size()]);
    }
}
