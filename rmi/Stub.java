package rmi;

import java.net.*;
import java.lang.reflect.*;
import java.io.*;
import java.util.Arrays;

/** RMI stub factory.

    <p>
    RMI stubs hide network communication with the remote server and provide a
    simple object-like interface to their users. This class provides methods for
    creating stub objects dynamically, when given pre-defined interfaces.

    <p>
    The network address of the remote server is set when a stub is created, and
    may not be modified afterwards. Two stubs are equal if they implement the
    same interface and carry the same remote server address - and would
    therefore connect to the same skeleton. Stubs are serializable.
 */
public abstract class Stub
{
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
    
    public static class RMIHandler implements java.lang.reflect.InvocationHandler {
        public InetSocketAddress address;
        
        public RMIHandler(InetSocketAddress address) {
            this.address = address;
        }
        
        public InetSocketAddress getAddress() {
            return address;
        }
        
        public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
            if(m.getName().equals("equals") && m.getReturnType().getName().equals("boolean") && args.length == 1) {
                Object other = args[0];

                if(other == null)
                    return false;
                    
                return (proxy.getClass().equals(other.getClass()) && (((RMIHandler) java.lang.reflect.Proxy.getInvocationHandler(proxy)).getAddress().toString()).equals(((RMIHandler) java.lang.reflect.Proxy.getInvocationHandler(other)).getAddress().toString()));
            }
            
            if(m.getName().equals("hashCode") && m.getReturnType().getName().equals("int") && args == null) {
                return 1013 * (proxy.getClass().hashCode()) ^ 1009 * (((RMIHandler) java.lang.reflect.Proxy.getInvocationHandler(proxy)).getAddress().hashCode());
            }
            
            if(m.getName().equals("toString") && m.getReturnType().equals(String.class) && args == null) {
                return proxy.getClass().toString() + ", via " + ((RMIHandler) java.lang.reflect.Proxy.getInvocationHandler(proxy)).getAddress().toString();
            }
            
            Object result = null;
            
            try {
                Socket socket = new Socket();
                socket.connect(address);
                ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
                oos.flush();
                ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
                MethodToCall message = new MethodToCall(m, args);
                oos.writeObject(message);
                oos.flush();
                
                try {
                    result = ois.readObject();
                }
                catch(IOException e) {
                    throw new RMIException("Error reading object");
                }
                catch(ClassNotFoundException r) {
                    throw new RMIException("Class not found");
                }

                ois.close();
                oos.close();
            } catch(IOException e) {
                throw new RMIException("IO Error");
            }
            
            if(result instanceof Throwable) {
                throw (Throwable) result;
            }
            return result;
        }
    
    }

    /** Creates a stub, given a skeleton with an assigned adress.

        <p>
        The stub is assigned the address of the skeleton. The skeleton must
        either have been created with a fixed address, or else it must have
        already been started.

        <p>
        This method should be used when the stub is created together with the
        skeleton. The stub may then be transmitted over the network to enable
        communication with the skeleton.

        @param c A <code>Class</code> object representing the interface
                 implemented by the remote object.
        @param skeleton The skeleton whose network address is to be used.
        @return The stub created.
        @throws IllegalStateException If the skeleton has not been assigned an
                                      address by the user and has not yet been
                                      started.
        @throws UnknownHostException When the skeleton address is a wildcard and
                                     a port is assigned, but no address can be
                                     found for the local host.
        @throws NullPointerException If any argument is <code>null</code>.
        @throws Error If <code>c</code> does not represent a remote interface
                      - an interface in which each method is marked as throwing
                      <code>RMIException</code>, or if an object implementing
                      this interface cannot be dynamically created.
     */
    public static <T> T create(Class<T> c, Skeleton<T> skeleton)
        throws UnknownHostException
    {
        if(c == null || skeleton == null) {
            throw new NullPointerException();
        }
        
        checkIfRemoteInterface(c);
        
        InetAddress localhost = InetAddress.getLocalHost();
        InetSocketAddress address = skeleton.getAddress();
        
        if(address == null) {
            throw new IllegalStateException();
        }
        
        Class[] interfaces = { c };
        
        return c.cast(java.lang.reflect.Proxy.newProxyInstance(
             c.getClassLoader(),
             interfaces,
             new RMIHandler(address)));
    }

    /** Creates a stub, given a skeleton with an assigned address and a hostname
        which overrides the skeleton's hostname.

        <p>
        The stub is assigned the port of the skeleton and the given hostname.
        The skeleton must either have been started with a fixed port, or else
        it must have been started to receive a system-assigned port, for this
        method to succeed.

        <p>
        This method should be used when the stub is created together with the
        skeleton, but firewalls or private networks prevent the system from
        automatically assigning a valid externally-routable address to the
        skeleton. In this case, the creator of the stub has the option of
        obtaining an externally-routable address by other means, and specifying
        this hostname to this method.

        @param c A <code>Class</code> object representing the interface
                 implemented by the remote object.
        @param skeleton The skeleton whose port is to be used.
        @param hostname The hostname with which the stub will be created.
        @return The stub created.
        @throws IllegalStateException If the skeleton has not been assigned a
                                      port.
        @throws NullPointerException If any argument is <code>null</code>.
        @throws Error If <code>c</code> does not represent a remote interface
                      - an interface in which each method is marked as throwing
                      <code>RMIException</code>, or if an object implementing
                      this interface cannot be dynamically created.
     */
    public static <T> T create(Class<T> c, Skeleton<T> skeleton,
                               String hostname)
    {
        if(c == null || skeleton == null || hostname == null) {
            throw new NullPointerException();
        }
        
        checkIfRemoteInterface(c);
        
        InetSocketAddress address = skeleton.getAddress();
        
        
        if(address == null || address.getPort() == -1) {
            throw new IllegalStateException();
        }
        
        Class[] interfaces = { c };
        
        return c.cast(java.lang.reflect.Proxy.newProxyInstance(
             c.getClassLoader(),
             interfaces,
             new RMIHandler(new InetSocketAddress(hostname, address.getPort()))));
    }

    /** Creates a stub, given the address of a remote server.

        <p>
        This method should be used primarily when bootstrapping RMI. In this
        case, the server is already running on a remote host but there is
        not necessarily a direct way to obtain an associated stub.

        @param c A <code>Class</code> object representing the interface
                 implemented by the remote object.
        @param address The network address of the remote skeleton.
        @return The stub created.
        @throws NullPointerException If any argument is <code>null</code>.
        @throws Error If <code>c</code> does not represent a remote interface
                      - an interface in which each method is marked as throwing
                      <code>RMIException</code>, or if an object implementing
                      this interface cannot be dynamically created.
     */
    public static <T> T create(Class<T> c, InetSocketAddress address)
    {
        if(c == null || address == null) {
            throw new NullPointerException();
        }
        
        checkIfRemoteInterface(c);
        
     
        Class[] interfaces = { c };
        
        return c.cast(java.lang.reflect.Proxy.newProxyInstance(
             c.getClassLoader(),
             interfaces,
             new RMIHandler(address)));
    }
}
