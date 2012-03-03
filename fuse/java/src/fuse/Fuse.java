package fuse;

import java.io.*;

import rmi.*;
import common.*;
import naming.*;
import storage.*;

/** FUSE client - Java portion.

    <p>
    This class implements several static methods, which are called directly by
    the FUSE driver to perform filesystem queries and operations. The methods
    are called by C++ code using the JNI Invocation API. In this way, the native
    code is able to use the RMI stubs for the naming and storage servers, even
    though RMI is implemented entirely in Java. The <code>Fuse</code> class
    itself should not be instantiated (and in fact cannot be, as it is
    abstract). It is only a container for, or grouping of, the methods that are
    needed by the native portion of the driver.

    <p>
    Strings passed by the C++ code are encoded in normal, unmodified UTF-8, and
    are passed as byte arrays. Where the Java code returns a string, it is also
    returned encoded in unmodified UTF-8 within a byte array. The byte arrays do
    not, unless otherwise specified, include terminating characters (such as
    null). String encoding and decoding is performed in Java, as Java provides
    good libraries for the purpose.

    <p>
    Exceptions raised by the methods in this class are translated by the C++
    code into POSIX error codes. For example, <code>FileNotFoundException</code>
    is translated to <code>ENOENT</code>, <code>IllegalArgumentException</code>
    is translated to <code>EINVAL</code>, and <code>RMIException</code> is
    translated to <code>EIO</code>. Unrecognized exceptions are translated to
    <code>EIO</code>.

    <p>
    Filesystem locking is not used, so the code here is subject to race
    conditions resulting from multiple users accessing the filesystem. As an
    example, a file might be found to exist when the naming server is contacted,
    yet when the storage server is contacted to retrieve file data, the file may
    already have been deleted by another user.

    <p>
    To use these methods, the native code should first call
    <code>initialize</code>. After that, any of the methods may be called in any
    order. All of the methods (except <code>initialize</code>) are guaranteed to
    be thread-safe, so no special locking provisions are needed in the native
    code.
 */
abstract class Fuse
{
    /** Stub for the naming server to be contacted. */
    private static Service  naming_server = null;

    /** Prevents any class from derived from <code>Fuse</code>. */
    private Fuse()
    {
    }

    /** Initializes the Java portion of the FUSE client.

        <p>
        This method simply creates a stub for the naming server with the given
        hostname, and with the default naming server client interface port. The
        server is not contacted - this method succeeds even if the server is
        unreachable.

        @param raw_hostname Byte array containing a UTF-8 string representing
                            the hostname of the naming server to be used for all
                            subsequent calls.
     */
    static void initialize(byte[] raw_hostname)
    {
        naming_server = NamingStubs.service(decode(raw_hostname));
    }

    /** Checks if the given path refers to a directory.

        @param raw_path Byte array containing a UTF-8 string representing the
                        path to be checked.
        @return <code>true</code> if the given object exists and is a directory,
                <code>false</code> if the object exists but is a file.
        @throws IllegalArgumentException If <code>raw_path</code> is not a valid
                                         filesystem path.
        @throws FileNotFoundException If the path does not refer to an existing
                                      object in the filesystem.
        @throws RMIException If the naming server cannot be contacted.
     */
    static boolean directory(byte[] raw_path)
        throws FileNotFoundException, RMIException
    {
        return naming_server.isDirectory(new Path(decode(raw_path)));
    }

    /** Returns the size of the given file.

        @param raw_path Byte array containing a UTF-8 string representing the
                        path to the file.
        @return The size of the file.
        @throws IllegalArgumentException If <code>raw_path</code> is not a valid
                                         filesystem path.
        @throws FileNotFoundException If the path does not refer to an existing
                                      file in the filesystem.
        @throws RMIException If the naming server or storage server cannot be
                             contacted.
     */
    static long size(byte[] raw_path)
        throws FileNotFoundException, RMIException
    {
        Path        path = new Path(decode(raw_path));
        Storage     storage_server = naming_server.getStorage(path);

        return storage_server.size(path);
    }

    /** Creates a file on the remote filesystem.

        @param raw_path Byte array containing a UTF-8 string representing the
                        path to the file to be created.
        @return <code>true</code> if the file has been created, and
                <code>false</code> otherwise. The file is not created if an
                object with the given path already exists.
        @throws IllegalArgumentException If <code>raw_path</code> is not a valid
                                         filesystem path, or if the path refers
                                         to the root directory.
        @throws IllegalStateException If no storage servers are connected to the
                                      naming server.
        @throws FileNotFoundException If the parent directory of the file does
                                      not exist.
        @throws RMIException If the naming server cannot be contacted.
     */
    static boolean createFile(byte[] raw_path)
        throws FileNotFoundException, RMIException
    {
        return naming_server.createFile(new Path(decode(raw_path)));
    }

    /** Creates a directory on the remote filesystem.

        @param raw_path Byte array containing a UTF-8 string representing the
                        path to the directory to be created.
        @return <code>true</code> if the directory has been created, and
                <code>false</code> otherwise. The directory is not created if an
                object with the given path already exists.
        @throws IllegalArgumentException If <code>raw_path</code> is not a valid
                                         filesystem path, or if the path refers
                                         to the root directory.
        @throws FileNotFoundException If the parent of the directory does not
                                      exist.
        @throws RMIException If the naming server cannot be contacted.
     */
    static boolean createDirectory(byte[] raw_path)
        throws FileNotFoundException, RMIException
    {
        return naming_server.createDirectory(new Path(decode(raw_path)));
    }

    /** Deletes an object on the remote filesystem.

        @param raw_path Byte array containing a UTF-8 string representing the
                        path to the object to be deleted.
        @return <code>true</code> if the object has been deleted, and
                <code>false</code> otherwise. It is not a normal error condition
                for deletion to fail with no exception raised.
        @throws IllegalArgumentException If <code>raw_path</code> is not a valid
                                         filesystem path, or if the path refers
                                         to the root directory.
        @throws FileNotFoundException If the object does not exist.
        @throws RMIException If the naming server cannot be contacted.
     */
    static boolean delete(byte[] raw_path)
        throws FileNotFoundException, RMIException
    {
        return naming_server.delete(new Path(decode(raw_path)));
    }

    /** Reads from a file on the remote filesystem.

        <p>
        If this method succeeds, it returns an array that is of either the
        requested length, or is of length equal to the number of bytes remaining
        in the file - whichever is smaller.

        @param raw_path Byte array containing a UTF-8 string representing the
                        path to the file to be read.
        @param offset Offset in the file at which the read is to begin.
        @param length Number of bytes to read from file.
        @param file_length The total length of the file, as known to the client.
                           This number is typically obtained by the FUSE client
                           when opening the file, and is used to prevent read
                           requests that extend past the end of file. In
                           general, however, it is not possible to prevent this,
                           as another client may replace the open file with
                           another of shorter length.
        @return Byte array containing bytes read from the file.
        @throws IllegalArgumentException If <code>raw_path</code> is not a valid
                                         filesystem path.
        @throws FileNotFoundException If the file does not exist.
        @throws IOException If reading the file causes an I/O error on the
                            storage server.
        @throws RMIException If the naming server or the storage server cannot
                             be contacted.
     */
    static byte[] read(byte[] raw_path, long offset, int length,
                       long file_length)
        throws FileNotFoundException, RMIException, IOException
    {
        // Decode the path and obtain a storage server stub.
        Path        path = new Path(decode(raw_path));
        Storage     storage_server = naming_server.getStorage(path);

        // Check that the starting offset is within the bounds of the file. If
        // it is beyond the end of the file, return the empty array.
        if(offset < 0)
            throw new IllegalArgumentException("offset is negative");

        if(offset >= file_length)
            return new byte[0];

        // Read bytes from file and return them.
        int         bytes_to_read = length;
        if(bytes_to_read > (file_length - offset))
            bytes_to_read = (int)(file_length - offset);

        return storage_server.read(path, offset, bytes_to_read);
    }

    /** Writes to a file on the remote filesystem.

        @param raw_path Byte array containing a UTF-8 string representing the
                        path to the file to be written to.
        @param offset Offset at which bytes are to be written to the file.
        @param buffer Buffer containing the bytes to be written.
        @throws IllegalArgumentException If <code>raw_path</code> is not a valid
                                         filesystem path.
        @throws FileNotFoundException If the file does not exist.
        @throws IOException If writing to the file causes an I/O error on the
                            storage server.
        @throws RMIException If the naming server or the storage server cannot
                             be contacted.
     */
    static void write(byte[] raw_path, long offset, byte[] buffer)
        throws FileNotFoundException, RMIException, IOException
    {
        Path        path = new Path(decode(raw_path));
        Storage     storage_server = naming_server.getStorage(path);

        storage_server.write(path, offset, buffer);
    }

    /** Lists a directory on the remote filesystem.

        @param raw_path Byte array containing a UTF-8 string representing the
                        path to the directory to be listed.
        @return List of children of the directory. The list is returned as a
                flattened sequence of null-terminated UTF-8 strings.
        @throws IllegalArgumentException If <code>raw_path</code> is not a valid
                                         filesystem path.
        @throws FileNotFoundException If the directory does not exist.
        @throws RMIException If the naming server or the storage server cannot
                             be contacted.
     */
    static byte[] list(byte[] raw_path)
        throws FileNotFoundException, RMIException
    {
        // Decode the path and the list the directory.
        String[]    children = naming_server.list(new Path(decode(raw_path)));
        byte[][]    encoded_children = new byte[children.length][];

        int         flattened_length = 0;

        // Encode each child entry as a UTF-8 string, and simultaneously compute
        // the number of bytes that will be taken by the flattened string
        // sequence. Children that cannot be encoded as UTF-8 strings without
        // embedded null characters are simply dropped and will not be
        // accessible to the FUSE client.
        for(int index = 0; index < children.length; ++index)
        {
            try
            {
                encoded_children[index] = encode(children[index]);
                flattened_length += encoded_children[index].length + 1;
            }
            catch(IllegalArgumentException e)
            {
                encoded_children[index] = null;
            }
        }

        // Copy the encoded child names into the flattened sequence.
        byte[]      flattened_children = new byte[flattened_length];
        int         offset = 0;

        for(int index = 0; index < encoded_children.length; ++index)
        {
            if(encoded_children[index] == null)
                continue;

            for(int byte_index = 0; byte_index < encoded_children[index].length;
                ++byte_index)
            {
                flattened_children[offset] =
                    encoded_children[index][byte_index];
                ++offset;
            }

            flattened_children[offset] = 0;
            ++offset;
        }

        // Return the child list.
        return flattened_children;
    }

    /** Decodes a UTF-8 string.

        <p>
        The behavior is undefined if the argument is not a valid UTF-8 string.
        If this becomes an issue, the <code>CharsetDecoder</code> should be used
        to do the decoding instead of the <code>String</code> constructor.

        <p>
        From the code, it may appear that the method is capable of throwing
        <code>Error</code>. <code>UTF-8</code> is a standard encoding that every
        Java implementation claims to support. It is indeed a serious internal
        error if the encoding is in fact not supported.

        @param raw_string A byte array containing the source UTF-8 string. The
                          string need not be null-terminated. The length of the
                          byte array is used to determine the end of the string.
                          If a terminator is present, it will also be present in
                          the decoded string.
        @return The decoded Java string.
     */
    private static String decode(byte[] raw_string)
    {
        try
        {
            return new String(raw_string, "UTF-8");
        }
        catch(UnsupportedEncodingException e)
        {
            throw new Error("UTF-8 encoding not supported", e);
        }
    }

    /** Encodes a Java string in UTF-8.

        <p>
        This method is the reverse of <code>decode</code>. As with
        <code>decode</code>, this method throws <code>Error</code> if the
        standard <code>UTF-8</code> encoding is not supported, because support
        for this encoding is an explicit requirement in the Java specification.

        <p>
        This method checks that the Java string to be encoded does not contain
        any embedded null characters. While this is not a problem for the
        encoding process itself, the result is intended for consumption by
        encoding-unaware C or C++ code, so embedded null characters are
        undesirable.

        @param string The Java string to be encoded.
        @return A byte array containing the encoded Java string. The byte array
                does not contain any special terminator characters.
        @throws IllegalArgumentException If the Java string contains an
                                         embedded null character.
     */
    private static byte[] encode(String string)
    {
        if(string.indexOf(0) != -1)
            throw new IllegalArgumentException("string contains embedded null");

        try
        {
            return string.getBytes("UTF-8");
        }
        catch(UnsupportedEncodingException e)
        {
            throw new Error("UTF-8 encoding not supported", e);
        }
    }
}
