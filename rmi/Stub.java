package rmi;

import java.net.*;
import java.lang.reflect.*;
import java.io.*;
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
		if(c == null || skeleton == null)
			throw new NullPointerException("c or skeleton null in Stub.create.\n");
	    Method m[] = c.getMethods();
	    for(Method v : m)
	    {
		   int yes = 0; 
   		   for(Class exceptions : v.getExceptionTypes())
		   {
			    
				if((exceptions.getName().equals("rmi.RMIException"))) 
				{
					yes = 1;
				}
		   }
		   if(yes == 0)
			   throw new Error();
     	}
		if(!skeleton.isStarted())
		{
			throw new IllegalStateException("IllegalState exception from Stub.create.\n");
		}
		if(skeleton.getAddress().getAddress().getLocalHost() == null)
		{
			throw new UnknownHostException("Unknowhost exception from Stub.create.\n");
		}
		ProxyClass<T> handler = new ProxyClass<T>(c,skeleton);
		
		T result = c.cast ((java.lang.reflect.Proxy.newProxyInstance(c.getClassLoader(),
						new Class[] { c }, handler)));
	    
		
		return result;
		    
	
    }
	
	private static class ProxyClass<T> implements InvocationHandler
	{
		
		private Class<T> c;
		private int port;
		private InetAddress address;
	
		public ProxyClass(Class<T> c, Skeleton<T> skeleton)
		{
			this.c = c;
			address = skeleton.getAddress().getAddress();
			port = skeleton.getAddress().getPort();
		}
		public ProxyClass(Class<T> c, InetSocketAddress a)
		{
			this.c = c;
			address = a.getAddress();
			port = a.getPort();
		}


		public InetAddress getAddress()
		{
			return address;
		}
		public int getPort()
		{
			return port;
		}
		public Object invoke(Object proxy, Method m, Object[] args) throws Throwable
		{
			if(m.getName().equals("equals"))
			{
				if(args[0] == null)
					return false;
			   ProxyClass<T> hand = (ProxyClass<T>)java.lang.reflect.Proxy.getInvocationHandler(args[0]);

				if(proxy.getClass().equals(args[0].getClass()))
				{
					if(hand.getAddress().equals(address) && hand.getPort() == port)
						return true;
					else
						return false;
				}
				else return false;
			}
			
			if(m.getName().equals("hashCode"))
			{
				ProxyClass chash = (ProxyClass)java.lang.reflect.Proxy.getInvocationHandler(proxy);
				return 2749*chash.getAddress().hashCode() + 3571*chash.getPort() + 
					139*c.hashCode();
			}

			if(m.getName().equals("toString"))
			{
				ProxyClass cString = (ProxyClass)java.lang.reflect.Proxy.getInvocationHandler(proxy);
				return cString.getAddress().toString() + "//" + cString.getPort() + " " + c.toString();
			}
			Socket clientSocket = null;
			ObjectOutputStream out = null;
			ObjectInputStream in = null;
	
			try{
				clientSocket = new Socket(address,port);
				out = new ObjectOutputStream(clientSocket.getOutputStream());
				out.flush();
				in = new ObjectInputStream(clientSocket.getInputStream());			
				Wrapper wrap = new Wrapper(m,args);
				out.writeObject(wrap);
			}catch(Exception e){
				throw new RMIException("Exception:" + e);
			}
			
			Object result = in.readObject();
			if(result instanceof InvocationTargetException)
			{
				throw(((InvocationTargetException)result).getTargetException());
			}

			try{
				out.close();
				in.close();
				clientSocket.close();
			}catch(Exception e){
				throw new RMIException(e);
			}
	
			return result;
		}
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
    	if(c == null || skeleton == null || hostname == null)
			throw new NullPointerException("c or skeleton or hostname null in Stub.create.\n");
	    Method m[] = c.getMethods();
	    for(Method v : m)
	    {
		   int yes = 0; 
   		   for(Class exceptions : v.getExceptionTypes())
		   {
			    
				if((exceptions.getName().equals("rmi.RMIException"))) 
				{
					yes = 1;
				}
		   }
		   if(yes == 0)
			   throw new Error();
     	}
		if(skeleton.getAddress().getPort() == 0)
		{
			throw new IllegalStateException("IllegalState exception from Stub.create.\n");
		}
		InetSocketAddress addr = new InetSocketAddress(hostname,skeleton.getAddress().getPort());
    	ProxyClass handler = new ProxyClass<T>(c,addr);
		T result = c.cast ((java.lang.reflect.Proxy.newProxyInstance(c.getClassLoader(),
						new Class[] { c }, handler)));
	    
		
		return result;
		
    
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
    	if(c == null || address == null)
			throw new NullPointerException("c or address null in Stub.create.\n");
	    Method m[] = c.getMethods();
	    for(Method v : m)
	    {
		   int yes = 0; 
   		   for(Class exceptions : v.getExceptionTypes())
		   {
			    
				if((exceptions.getName().equals("rmi.RMIException"))) 
				{
					yes = 1;
				}
		   }
		   if(yes == 0)
			   throw new Error();
     	}
		ProxyClass handler = new ProxyClass<T>(c,address);
		T result = c.cast ((java.lang.reflect.Proxy.newProxyInstance(c.getClassLoader(),
						new Class[] { c }, handler)));
	    
		
		return result;
		
    }
}



