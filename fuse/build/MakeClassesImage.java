package build;

import java.io.*;
import java.util.*;

/** Tool for quoting Java classes as C byte arrays.

    <p>
    To run this from the command line, supply a list of arguments as follows:
    for each class file that is to be quoted, provide the class name as one
    argument, and the path to the class file as the next argument. Arguments
    therefore come in pairs. Class names including packages should use the
    forward slash (<code>/</code>) instead of the dot (<code>.</code>) for the
    module separator. For example, to specify that the class
    <code>fuse.Fuse</code> should be included, one would provide the two command
    line arguments <code>fuse/Fuse java/src/fuse/Fuse.class</code>.

    <p>
    The tool's output is written to standard output. This should be redirected
    to a <code>.c</code> or <code>.cpp</code> file, compiled, and linked with
    the program making use of the inlined classes. Definitions of the structure
    type used for the class table, and the symbol of the class table and class
    count, are found in the file <code>java-classes.h</code>.

    <p>
    Class names are output in Java's modified UTF-8. This makes the names
    suitable for direct use with the JNI function <code>DefineClass</code>.

    <p>
    The program operates in two passes. First, it loads all the classes and
    encodes all the class names. Then, the C code that would allow the classes
    to be inlined is written to standard output.
 */
public class MakeClassesImage
{
    /** Exit code indicating success. */
    private static final int    EXIT_SUCCESS = 0;
    /** Exit code indicating failure. */
    private static final int    EXIT_FAILURE = 2;

    /** Number of bytes to print per row of output. */
    private static final int    BYTES_PER_ROW = 15;
    /** Four-space tab. */
    private static final String TAB = "    ";

    /** Tool entry point.

        @param arguments Command line arguments. Should be a series of pairs of
                         a class name and a path to the corresponding class
                         file.
     */
    public static void main(String[] arguments)
    {
        // Check that arguments are provided in pairs.
        if((arguments.length % 2) != 0)
        {
            System.err.println("arguments must be supplied as pairs of a " +
                               "class name followed by a path");
            System.exit(EXIT_FAILURE);
        }

        // Load all classes specified on the command line. The ClassInformation
        // objects contain the name of each class and the contents of the class
        // file.
        List<ClassInformation>  classes = new ArrayList<ClassInformation>();

        for(int argument_index = 0; argument_index < arguments.length;
            argument_index += 2)
        {
            try
            {
                classes.add(load(arguments[argument_index],
                                 arguments[argument_index + 1]));
            }
            catch(Throwable t)
            {
                System.err.println("cannot load class " +
                                   arguments[argument_index] + " from file " +
                                   arguments[argument_index + 1] + ": " + t);
                System.exit(EXIT_FAILURE);
            }
        }

        // Write the output file header (include files and comments go here).
        writeHeader(System.out);

        // Format and output C source code for the arrays containing each class
        // and each class name.
        for(int class_index = 0; class_index < classes.size(); ++class_index)
            writeClass(System.out, classes.get(class_index), class_index);

        // Write the class table.
        writeTable(System.out, classes);

        System.exit(EXIT_SUCCESS);
    }

    /** Writes the generated file header.

        <p>
        The header includes a comment warning any user that the file was
        automatically generated, and includes some other files.

        @param stream Output stream to which the header should be written.
     */
    private static void writeHeader(PrintStream stream)
    {
        stream.println("// This is an automatically-generated file " +
                       "containing Java classes.");
        stream.println();
        stream.println("#include <stdlib.h>");
        stream.println("#include \"java-classes.h\"");
        stream.println();
    }

    /** Writes a single class and its name.

        @param stream Output stream to which the class should be written.
        @param info Class name and class file contents.
        @param class_index Index of the class within the table. This index
                           becomes part of the name of the class body and name
                           arrays.
     */
    private static void writeClass(PrintStream stream, ClassInformation info,
                                   int class_index)
    {
        writeBuffer(stream, bodySymbol(class_index), info.body);
        stream.println();

        writeBuffer(stream, nameSymbol(class_index), info.name);
        stream.println();
    }

    /** Writes C source code for a byte array.

        @param stream Output stream to which the byte array should be written.
        @param symbol Name with which the byte array should be declared.
        @param data Bytes to be placed in the byte array.
     */
    private static void writeBuffer(PrintStream stream, String symbol,
                                    byte[] data)
    {
        // Write the start of the declaration.
        stream.print("static const char   " + symbol + "[] =");

        if(data.length == 0)
        {
            stream.println(" {};");
            return;
        }

        stream.println();
        stream.println("{");
        stream.print(TAB);

        // Write the initializer for the byte array.
        int                     offset = 0;
        int                     row_offset = 0;

        while(offset < data.length)
        {
            stream.format("0x%02X", data[offset]);

            ++offset;
            ++row_offset;

            if(offset < data.length)
            {
                stream.print(",");

                if(row_offset >= BYTES_PER_ROW)
                {
                    stream.println();
                    stream.print(TAB);

                    row_offset = 0;
                }
            }
        }

        stream.println();
        stream.println("};");
    }

    /** Writes the class table.

        <p>
        Each entry in the class table consists of pointers to the class name and
        class body, and gives the length of the class body. It is so formatted
        for easy use by the JNI function <code>DefineClass</code>. This function
        also writes C code to declare an integer <code>class_count</code>, which
        gives the number of inlined classes, which is also the number of classes
        in the class table.

        @param stream Output stream to which the class table should be written.
        @param classes List of <code>ClassInforation</code> objects, one for
                       each of the classes that is being inlined into the
                       native binary image.
     */
    private static void writeTable(PrintStream stream,
                                   List<ClassInformation> classes)
    {
        // Declare and define the number of classes in the class table.
        stream.print("const int           class_count = " + classes.size());
        stream.println(";");
        stream.println();

        // Begin the class table declaration.
        stream.print("const class_info    classes[] =");

        if(classes.size() == 0)
        {
            stream.println(" {};");
            return;
        }

        stream.println();
        stream.print(TAB + "{");

        // Write all the class table entries.
        int                     class_index = 0;

        for(ClassInformation info : classes)
        {
            stream.print("{");
            stream.format("%-7s ", nameSymbol(class_index) + ",");
            stream.format("%-7s ", bodySymbol(class_index) + ",");
            stream.format("0x%2X", info.body.length);
            stream.print("}");

            ++class_index;

            if(class_index < classes.size())
            {
                stream.println(",");
                stream.print(TAB + " ");
            }
        }

        stream.println("};");
    }

    /** Gives the name of the C array for the body of a class.

        @param class_index Index of the class in the class table.
        @return A string of the form <code>body</code><em>i</code>, where
                <em>i</code> is the index of the class.
     */
    private static String bodySymbol(int class_index)
    {
        return ("body" + class_index);
    }

    /** Gives the name of the C array for the encoded name of a class.

        @param class_index Index of the class in the class table.
        @return A string of the form <code>name</code><em>i</code>, where
                <em>i</code> is the index of the class.
     */
    private static String nameSymbol(int class_index)
    {
        return ("name" + class_index);
    }

    /** Loads a class from file and encodes its name in modified UTF-8.

        @param class_name Name of the class to be loaded.
        @param path Path to the <code>.class</code> file for the class.
        @return A <code>ClassInformation</code> object containing the encoded
                class name and class body.
        @throws FileNotFoundException If the given class file cannot be found,
                                      or if the path refers to a directory.
        @throws IOException If the given class file cannot be read.
     */
    private static ClassInformation load(String class_name, String path)
        throws FileNotFoundException, IOException
    {
        // Parse the path to the class file.
        File                    class_file = new File(path);

        // Retrieve the class file length.
        long                    long_file_length = class_file.length();
        if(long_file_length == -1)
            throw new FileNotFoundException("class file does not exist");

        if(long_file_length > Integer.MAX_VALUE)
            throw new IOException("class file too large");

        int                     file_length = (int)long_file_length;

        // Open the class file for reading.
        byte[]                  class_body;
        InputStream             class_input_stream =
            new FileInputStream(class_file);

        try
        {
            int                 total_bytes_read = 0;
            int                 bytes_read;

            // Allocate enough space for the entire contents of the class body.
            class_body = new byte[file_length];

            // Read the class body into the buffer just allocated.
            while(total_bytes_read < file_length)
            {
                bytes_read =
                    class_input_stream.read(class_body, total_bytes_read,
                                            file_length - total_bytes_read);
                if(bytes_read == -1)
                    throw new IOException("unexpected end of file");

                total_bytes_read += bytes_read;
            }
        }
        finally
        {
            try
            {
                class_input_stream.close();
            }
            catch(Throwable t) { }
        }

        // Encode the class name in modified UTF-8. The writeUTF method of
        // DataOutputStream guarantees output in modified UTF-8 - this is why
        // this awkward method of obtaining a byte buffer from the class name
        // string is employed.
        ByteArrayOutputStream   name_buffer = new ByteArrayOutputStream();
        DataOutputStream        encoding_stream =
            new DataOutputStream(name_buffer);

        encoding_stream.writeUTF(class_name);

        // Following the call to writeUTF, the byte array underlying
        // name_buffer contains a short integer giving the number of bytes that
        // the encoded string takes up, followed by the encoded string itself.
        // Extract the string only. Also, include a null terminator in the
        // string for compatibility with DefineClass. This terminator will be
        // inserted automatically by copyOfRange when it is asked to copy from
        // beyond the end of raw_encoded_name.
        byte[]                  raw_encoded_name = name_buffer.toByteArray();
        byte[]                  final_encoded_name =
            Arrays.copyOfRange(raw_encoded_name, 2,
                               raw_encoded_name.length + 1);

        encoding_stream.close();
        name_buffer.close();

        return new ClassInformation(final_encoded_name, class_body);
    }

    /** Pair of encoded class name and class file contents. */
    private static class ClassInformation
    {
        /** Class name, encoded in Java modified UTF-8. */
        final byte[]            name;
        /** Class body, taken directly from the contents of the
            <code>.class</code> file. */
        final byte[]            body;

        /** Creates a <code>ClassInformation</code> object, given an encoded
            class name and class body.

            @param name Encoded class name.
            @param body Class body (<code>.class</code> file contents).
         */
        ClassInformation(byte[] name, byte[] body)
        {
            this.name = name;
            this.body = body;
        }
    }
}
