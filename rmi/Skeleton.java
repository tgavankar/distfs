package rmi;

import java.net.*;
import java.lang.*;
import java.lang.reflect.*;
import java.io.*;
import java.util.ArrayList;

/** RMI skeleton

    <p>
    A skeleton encapsulates a multithreaded TCP server. The server's clients are
    intended to be RMI stubs created using the <code>Stub</code> class.

    <p>
    The skeleton class is parametrized by a type variable. This type variable
    should be instantiated with an interface. The skeleton will accept from the
    stub requests for calls to the methods of this interface. It will then
    forward those requests to an object. The object is specified when the
    skeleton is constructed, and must implement the remote interface. Each
    method in the interface should be marked as throwing
    <code>RMIException</code>, in addition to any other exceptions that the user
    desires.

    <p>
    Exceptions may occur at the top level in the listening and service threads.
    The skeleton's response to these exceptions can be customized by deriving
    a class from <code>Skeleton</code> and overriding <code>listen_error</code>
    or <code>service_error</code>.
*/
public class Skeleton<T>
{
    Class<T> c;
    T server;
    Listener<T> listenServer;
    Thread listenThread;
    InetSocketAddress address;

    public synchronized boolean isListenerActive() {
        return listenServer.isFullyActive();
    }
    
    public synchronized InetSocketAddress getAddress() {
        return this.address;
    }
    
    private static <T> void checkIfRemoteInterface(Class<T> c) throws Error {
        Method methods[] = c.getDeclaredMethods();
        for (int i=0; i < methods.length; i++) {  
            Method m = methods[i];
            Class exc[] = m.getExceptionTypes();
            boolean is_remote = false;
            for(int j=0; j < exc.length; j++) {
                if(exc[j].getName().equals("rmi.RMIException")) {
                    is_remote = true;
                    break;
                }
            }
            if(!is_remote) {
                throw new Error();
            }
        }
    }
    
    
    /** Creates a <code>Skeleton</code> with no initial server address. The
        address will be determined by the system when <code>start</code> is
        called. Equivalent to using <code>Skeleton(null)</code>.

        <p>
        This constructor is for skeletons that will not be used for
        bootstrapping RMI - those that therefore do not require a well-known
        port.

        @param c An object representing the class of the interface for which the
                 skeleton server is to handle method call requests.
        @param server An object implementing said interface. Requests for method
                      calls are forwarded by the skeleton to this object.
        @throws Error If <code>c</code> does not represent a remote interface -
                      an interface whose methods are all marked as throwing
                      <code>RMIException</code>.
        @throws NullPointerException If either of <code>c</code> or
                                     <code>server</code> is <code>null</code>.
     */
    public Skeleton(Class<T> c, T server)
    {
        if(c == null || server == null) {
            throw new NullPointerException();
        }
       
        checkIfRemoteInterface(c);

        this.c = c;
        this.server = server;
        this.listenServer = new Listener<T>(address, this);
        this.listenThread = new Thread(listenServer);
    }

    
    /** Creates a <code>Skeleton</code> with the given initial server address.

        <p>
        This constructor should be used when the port number is significant.

        @param c An object representing the class of the interface for which the
                 skeleton server is to handle method call requests.
        @param server An object implementing said interface. Requests for method
                      calls are forwarded by the skeleton to this object.
        @param address The address at which the skeleton is to run. If
                       <code>null</code>, the address will be chosen by the
                       system when <code>start</code> is called.
        @throws Error If <code>c</code> does not represent a remote interface -
                      an interface whose methods are all marked as throwing
                      <code>RMIException</code>.
        @throws NullPointerException If either of <code>c</code> or
                                     <code>server</code> is <code>null</code>.
     */
    public Skeleton(Class<T> c, T server, InetSocketAddress address)
    {
        if(c == null || server == null) {
            throw new NullPointerException();
        }

        checkIfRemoteInterface(c);

        this.c = c;
        this.server = server;
        this.address = address;
        this.listenServer = new Listener<T>(address, this);
        this.listenThread = new Thread(listenServer);
    }

    /** Called when the listening thread exits.

        <p>
        The listening thread may exit due to a top-level exception, or due to a
        call to <code>stop</code>.

        <p>
        When this method is called, the calling thread owns the lock on the
        <code>Skeleton</code> object. Care must be taken to avoid deadlocks when
        calling <code>start</code> or <code>stop</code> from different threads
        during this call.

        <p>
        The default implementation does nothing.

        @param cause The exception that stopped the skeleton, or
                     <code>null</code> if the skeleton stopped normally.
     */
    protected void stopped(Throwable cause)
    {
    }

    /** Called when an exception occurs at the top level in the listening
        thread.

        <p>
        The intent of this method is to allow the user to report exceptions in
        the listening thread to another thread, by a mechanism of the user's
        choosing. The user may also ignore the exceptions. The default
        implementation simply stops the server. The user should not use this
        method to stop the skeleton. The exception will again be provided as the
        argument to <code>stopped</code>, which will be called later.

        @param exception The exception that occurred.
        @return <code>true</code> if the server is to resume accepting
                connections, <code>false</code> if the server is to shut down.
     */
    protected boolean listen_error(Exception exception)
    {
        return false;
    }

    /** Called when an exception occurs at the top level in a service thread.

        <p>
        The default implementation does nothing.

        @param exception The exception that occurred.
     */
    protected void service_error(RMIException exception)
    {
    }

    /** Starts the skeleton server.

        <p>
        A thread is created to listen for connection requests, and the method
        returns immediately. Additional threads are created when connections are
        accepted. The network address used for the server is determined by which
        constructor was used to create the <code>Skeleton</code> object.

        @throws RMIException When the listening socket cannot be created or
                             bound, when the listening thread cannot be created,
                             or when the server has already been started and has
                             not since stopped.
     */
    public synchronized void start() throws RMIException
    {
        try {
            this.address = this.listenServer.bind();
            this.listenThread.start();  
        }
        catch(IllegalThreadStateException e) {
            throw new RMIException("Thread was already started.");
        }

        try {
            Thread.sleep(10);  // listener is synchronized as is start(), so sleep to let listener start
        }
        catch(InterruptedException e) {
            throw new RMIException("Start thread interrputed");
        }

        return;
        
    }

    /** Stops the skeleton server, if it is already running.

        <p>
        The listening thread terminates. Threads created to service connections
        may continue running until their invocations of the <code>service</code>
        method return. The server stops at some later time; the method
        <code>stopped</code> is called at that point. The server may then be
        restarted.
     */
    public synchronized void stop()
    {
        this.listenServer.stop();
        while(this.listenServer.isFullyActive()) { }
        this.stopped(null);
    }
    
    public class WorkerRunnable<T> implements Runnable{

        protected Socket clientSocket;
        protected Skeleton<T> skeleton;
        

        public WorkerRunnable(Socket clientSocket, Skeleton<T> skeleton) {
            this.clientSocket = clientSocket;
            this.skeleton = skeleton;
        }

        public void run() {
            try {
                ObjectOutputStream oos = new ObjectOutputStream(clientSocket.getOutputStream());
                oos.flush();
                ObjectInputStream ois = new ObjectInputStream(clientSocket.getInputStream());
                
                MethodToCall message = null;
                Object result = null;
                
                try {
                    message = (MethodToCall) ois.readObject();
                    Method m = null;
                    try {
                        m = server.getClass().getMethod(message.getMethod(), message.getTypes());
                    }
                    catch(NoSuchMethodException e) {
                        result = e;
                    }
                    Object[] args = message.getArgs();
                    
                    try {
                        synchronized(this) {
                            if(result == null) {
                                try {
                                    result = m.invoke(server, args);
                                }
                                catch(InvocationTargetException e) {
                                    result = e.getTargetException();
                                }
                            }
                        }
                    }
                    catch(Throwable e) {
                        result = e;
                    }
                }
                catch(IOException e) {
                    result = new RMIException("IOException");
                }
                catch(ClassNotFoundException r) {
                    result = new RMIException("Class not found");
                }
                
                oos.writeObject(result);
                oos.flush();
                
                ois.close();
                oos.close();
            } catch (IOException e) {
                this.skeleton.service_error(new RMIException("IOException"));
            }
        }
    }
    
    private class Listener<T> implements Runnable {
        protected InetSocketAddress address;
        protected ServerSocket serverSocket;
        protected Skeleton<T> skeleton;
        protected boolean isStopped;  // command to stop listening
        protected Thread currentThread;
        protected volatile ArrayList<Thread> workers;
        protected volatile boolean isFullyActive;  // has actually stopped listening/working
        
        public Listener(InetSocketAddress address, Skeleton<T> skeleton) {
            this.address = address;
            this.skeleton = skeleton;
            this.isStopped = false;
            this.workers = new ArrayList<Thread>();
            try {
                this.serverSocket = new ServerSocket();
            } catch (IOException e) {
                this.skeleton.listen_error(new RMIException("Cannot create new ServerSocket"));
            }
        }
                
        public synchronized InetSocketAddress getRunningAddress() {
            InetAddress localip = serverSocket.getInetAddress();
            int port = serverSocket.getLocalPort();
            if(port == -1) {
                return null;
            }
            return new InetSocketAddress(localip, port);
        }
        
        public synchronized InetSocketAddress bind() {
            try {
                this.serverSocket.bind(this.address);
            } catch (IOException e) {
                this.skeleton.listen_error(new RMIException("Cannot open port"));
            }
            
            return getRunningAddress();
        }
        
        public void run() {
            synchronized(this) {
                this.currentThread = Thread.currentThread();
            }

            try {
                this.serverSocket.setSoTimeout(50);
            } catch (SocketException e) {
                if(!this.skeleton.listen_error(new RMIException("Cannot set socket timeout"))) {
                    try {
                        this.serverSocket.close();
                    } catch (IOException f) {
                        if(!this.skeleton.listen_error(new RMIException("Error closing server"))) {
                            return;
                        }
                    }
                    this.isFullyActive = false;
                    return;
                }
            }
            while(!isStopped()){
                Socket clientSocket = null;
                try {
                    synchronized(this) {
                        this.isFullyActive = true;
                        clientSocket = this.serverSocket.accept();
                    }
                } catch(SocketTimeoutException e) {
                    if(isStopped()) {
                        break;
                    }
                    else {
                        continue;
                    }
                } catch (IOException e) {
                    if(isStopped()) {
                        this.isFullyActive = false;
                        break;
                    }
                    if(!this.skeleton.listen_error(new RMIException("Error accepting client connection"))) {
                        this.isFullyActive = false;
                        break;
                    }
                }
               
                Thread worker = new Thread(new WorkerRunnable<T>(clientSocket, this.skeleton));
                worker.start();
                workers.add(worker);
            }

            for(Thread w : workers) {
                try {
                    w.join();
                }
                catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            try {
                this.serverSocket.close();
            } catch (IOException e) {
                if(!this.skeleton.listen_error(new RMIException("Error closing server"))) {
                    this.skeleton.stop();
                }
            }
            this.isFullyActive = false;
        }
        
        public synchronized boolean isFullyActive() {
            return this.isFullyActive;
        }
        
        private synchronized boolean isStopped() {
            return this.isStopped;
        }

        public void stop(){
            this.isStopped = true;
        }

    }
}