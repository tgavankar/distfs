package rmi;

import java.net.*;
import java.lang.reflect.*;
import java.io.*;
public class Wrapper implements Serializable
{
		private String name;
		private Class[] pTypes;
		private Object[] p;
		public Wrapper(Method m, Object[] parameters)
		{
			name = m.getName();
			p = parameters;
			pTypes = m.getParameterTypes();
		}
		public String getName()
		{
			return name;
		}
		public Class[] getpTypes()
		{
			return pTypes;
		}
		public Object[] getP()
		{
			return p;
		}
}
