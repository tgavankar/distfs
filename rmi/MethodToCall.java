package rmi;

import java.net.*;
import java.lang.reflect.*;
import java.io.*;
import java.util.Arrays;


public class MethodToCall implements Serializable
{
    String m;
    Class[] types;
    Object[] args;
    
    public MethodToCall(Method m, Object[] args) {
        this.m = m.getName();
        this.types = m.getParameterTypes();
        this.args = args;
    }
    
    public String getMethod() {
        return m;
    }
    
    public Object[] getArgs() {
        return args;
    }
    
    public Class[] getTypes() {
        return types;
    }

}
