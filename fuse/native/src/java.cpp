/** @file java.cpp
    @brief Implementation of the JNI wrapper.

    This implementation occasionally does redundant work (such as repeatedly
    making JNI calls to obtain a pointer to the JNI environment). This done in
    order to present a simple interface to the user. Where the interface would
    be simplified at a small cost in performance, interface simplicity is
    preferred.

    Templates are used to wrap functions from the JNI function families (such as
    <code>CallStaticMethod</code>, <code>GetField</code>, etc.) that contain a
    single function for each of several different JNI types. This is the only
    substantial use of C++-specific features in the entire FUSE driver, and the
    only reason C++ was chosen. The driver, on the whole, is written in the
    "flavor" of C++ that is C with some extra features. A better JNI wrapper
    might use templates, boost, exceptions, and some boost-inspired generic code
    to greatly simplify the interface.
 */

#include <cstring>
#include <cerrno>
#include <jni.h>
#include "common.h"
#include "java.h"
#include "java-classes.h"

/** @name Java Virtual Machine */
//@{
/// Java virtual machine version expected by this JNI wrapper.
#define JNI_REQUIRED    JNI_VERSION_1_6
/// Name of the Java class implementing the Java portions of the FUSE driver.
#define CLASS_NAME      "fuse/Fuse"
/// Java virtual machine.
static JavaVM           *virtual_machine;
//@}

/** @name Exception Table

    The wrapper translates certain Java exceptions to POSIX error codes. A table
    is provided for this purpose. Exceptions not listed in this table are
    assigned a default error code.
 */
//@{
/// POSIX error value for unrecognized Java exceptions.
#define DEFAULT_ERROR   EIO
/// Number of recognized Java exceptions.
#define ERROR_COUNT     3

static const struct
{
    /// Exception class name.
    const char  *name;
    /// Corresponding POSIX error code.
    int         code;
}
    /** @brief Exception translation table.

        If two exceptions listed in this table have a subclass relation, the
        subclass must be listed before the superclass. Otherwise, the entry for
        the subclass will never be considered.
     */
    exceptions[ERROR_COUNT] =
        {{"java/lang/IllegalArgumentException",  EINVAL},
         {"java/lang/IndexOutOfBoundsException", EINVAL},
         {"java/io/FileNotFoundException",       ENOENT}};
//@}

// Non-static functions are documented in java.h.

static bool java_record_exception(JNIEnv *environment, jthrowable &exception);

/** @name Initialization and Cleanup */
//@{
bool java_initialize()
{
    JNIEnv              *environment;
    JavaVMInitArgs      arguments;
    jint                result;

    // Obtain default arguments parameter for a Java 1.6 virtual machine.
    memset(&arguments, 0, sizeof(arguments));
    arguments.version = JNI_REQUIRED;

    result = JNI_GetDefaultJavaVMInitArgs(&arguments);
    if(result != JNI_OK)
        return false;

    // Create a Java 1.6 virtual machine.
    result =
        JNI_CreateJavaVM(&virtual_machine, (void**)&environment, &arguments);
    if(result != JNI_OK)
        return false;

    // Detach the current thread from the virtual machine and return.
    java_detach();
    return true;
}

// This function calls the JNI routine DefineClass for each class in the
// in-memory class table (see java-classes.h). The class loader parameter passed
// to define class is the result of calling ClassLoader.getSystemClassLoader().
jthrowable java_load_classes()
{
    JNIEnv              *environment;
    jclass              class_loader;
    jmethodID           get_system_loader;
    jobject             system_loader;
    jthrowable          exception;
    jint                jni_result;

    // Get the JNI environment structure for the current thread.
    jni_result = virtual_machine->GetEnv((void**)&environment, JNI_REQUIRED);
    if(jni_result != JNI_OK)
        return false;

    // Find the standard ClassLoader class.
    class_loader = environment->FindClass("java/lang/ClassLoader");
    if(class_loader == NULL)
    {
        java_record_exception(environment, exception);
        return exception;
    }

    // Get the system class loader. The classes loaded from memory will be
    // marked as having been loaded using this class loader.
    get_system_loader =
        environment->GetStaticMethodID(class_loader, "getSystemClassLoader",
                                       "()Ljava/lang/ClassLoader;");
    if(get_system_loader == NULL)
    {
        java_record_exception(environment, exception);
        return exception;
    }

    system_loader =
        environment->CallStaticObjectMethod(class_loader, get_system_loader);

    // Iterate through the in-memory class table. For each entry, load the
    // corresponding class.
    for(int class_index = 0; class_index < class_count; ++class_index)
    {
        if(environment->DefineClass(classes[class_index].name, system_loader,
                                    (const jbyte*)classes[class_index].body,
                                    classes[class_index].body_length) == NULL)
        {
            java_record_exception(environment, exception);
            return exception;
        }
    }

    return NULL;
}

void java_destroy()
{
    virtual_machine->DestroyJavaVM();
}
//@}

/** @name Thread Management */
//@{
bool java_attach()
{
    JNIEnv              *environment;
    jint                result;

    result = virtual_machine->AttachCurrentThread((void**)&environment, NULL);

    return result == JNI_OK;
}

void java_detach()
{
    virtual_machine->DetachCurrentThread();
}
//@}

/** @name Exception Handling */
//@{
int java_error_code(jthrowable exception)
{
    JNIEnv              *environment;
    jclass              cls;
    int                 counter;
    jint                jni_result;

    // Retrieve the JNI environment for the current thread.
    jni_result = virtual_machine->GetEnv((void**)&environment, JNI_REQUIRED);
    if(jni_result != JNI_OK)
        return DEFAULT_ERROR;

    // Search the exception table for a class that is either the same as the
    // class of the exception, or a superclass. If such a class is found, return
    // the corresponding error code. Otherwise, return the default error code.
    for(counter = 0; counter < ERROR_COUNT; ++counter)
    {
        cls = environment->FindClass(exceptions[counter].name);
        if(cls == NULL)
            continue;

        if(environment->IsInstanceOf(exception, cls))
            return exceptions[counter].code;
    }

    return DEFAULT_ERROR;
}

// The effect of this function is the same as of the following Java code, which
// filename is a byte array:
//  String          java_filename = new String(filename);
//  OutputStream    output_stream = new FileOutputStream(java_filename, true);
//  PrintWriter     writer = new PrintWriter(output_stream);
//  if(stack_trace)
//      exception.printStackTrace(writer);
//  else
//      writer.println(exception);
void java_describe_exception(jthrowable exception, const char *filename,
                             bool stack_trace)
{
    JNIEnv              *environment;
    jint                jni_result;

    // Retrieve JNI environment pointer for the current thread.
    jni_result = virtual_machine->GetEnv((void**)&environment, JNI_REQUIRED);
    if(jni_result != JNI_OK)
        return;

    jobject             byte_array;

    // Convert the C filename to a Java byte array.
    byte_array = java_encode(filename);
    if(byte_array == NULL)
        return;

    jclass              string;
    jmethodID           string_constructor;
    jobject             java_filename;

    // Create a Java string from the Java byte array. This converts from the
    // system encoding to the Java encoding.
    string = environment->FindClass("java/lang/String");
    if(string == NULL)
        return;

    string_constructor = environment->GetMethodID(string, "<init>", "([B)V");
    if(string_constructor == NULL)
        return;

    java_filename =
        environment->NewObject(string, string_constructor, byte_array);
    if(java_filename == NULL)
        return;

    // Find the FileOutputStream class and retrieve method IDs for needed
    // methods. The output stream itself is not created at this point to
    // minimize the amount of cleanup code that will have to be called if a
    // subsequent operation fails. The output stream is created as late as
    // possible.
    jclass              file_output_stream;
    jmethodID           file_output_stream_constructor;
    jmethodID           close_output_stream;

    file_output_stream = environment->FindClass("java/io/FileOutputStream");
    if(file_output_stream == NULL)
        return;

    file_output_stream_constructor =
        environment->GetMethodID(file_output_stream, "<init>",
                                 "(Ljava/lang/String;Z)V");
    if(file_output_stream_constructor == NULL)
        return;

    close_output_stream =
        environment->GetMethodID(file_output_stream, "close", "()V");
    if(close_output_stream == NULL)
        return;

    // Find the PrintWriter class and retrieve method IDs.
    jclass              print_writer;
    jmethodID           print_writer_constructor;
    jmethodID           println;
    jmethodID           close_print_writer;

    print_writer = environment->FindClass("java/io/PrintWriter");
    if(print_writer == NULL)
        return;

    print_writer_constructor =
        environment->GetMethodID(print_writer, "<init>",
                                 "(Ljava/io/OutputStream;)V");
    if(print_writer_constructor == NULL)
        return;

    println = environment->GetMethodID(print_writer, "println",
                                      "(Ljava/lang/Object;)V");
    if(println == NULL)
        return;

    close_print_writer =
        environment->GetMethodID(print_writer, "close", "()V");
    if(close_print_writer == NULL)
        return;

    // Retrieve the exception class.
    jclass              cls = environment->GetObjectClass(exception);
    jmethodID           print_stack_trace =
        environment->GetMethodID(cls, "printStackTrace",
                                 "(Ljava/io/PrintWriter;)V");
    if(print_stack_trace == NULL)
        return;

    // Open the output stream and create the PrintWriter. From the point on,
    // cleanup code is necessary to close the output stream and the PrintWriter
    // if a subsequent operation fails.
    jobject             output_stream = NULL;
    jobject             writer = NULL;

    output_stream =
        environment->NewObject(file_output_stream,
                               file_output_stream_constructor,
                               java_filename, JNI_TRUE);
    if(output_stream == NULL)
        return;

    writer =
        environment->NewObject(print_writer, print_writer_constructor,
                               output_stream);
    if(writer == NULL)
    {
        environment->CallVoidMethod(output_stream, close_output_stream);
        return;
    }

    // Either convert the exception itself to a string and print it, or print
    // the entire exception stack trace.
    if(!stack_trace)
        environment->CallVoidMethod(writer, println, exception);
    else
        environment->CallVoidMethod(exception, print_stack_trace, writer);

    // Close the PrintWriter and the output stream.
    environment->CallVoidMethod(writer, close_print_writer);
    environment->CallVoidMethod(output_stream, close_output_stream);
}

/** @brief Retrieves the current Java exception, if there is one, and stores it
    for use by native code.

    This function affects only the current thread. If an exception was present,
    the exception is cleared.

    @param environment JNI environment for the current thread.
    @param exception Reference to a location to receive the Java exception
                     object.
 */
static bool java_record_exception(JNIEnv *environment, jthrowable &exception)
{
    exception = environment->ExceptionOccurred();
    environment->ExceptionClear();

    return exception != NULL;
}
//@}

/** @name Strings and Buffers */
//@{
jobject java_encode(const char *buffer, size_t length)
{
    JNIEnv              *environment;
    jbyteArray          array;
    jbyte               *elements;
    jint                jni_result;

    // Retrieve the JNI environment for the current thread.
    jni_result = virtual_machine->GetEnv((void**)&environment, JNI_REQUIRED);
    if(jni_result != JNI_OK)
        return NULL;

    // Create a new Java byte array of the given length.
    array = environment->NewByteArray(length);
    if(array == NULL)
        return NULL;

    // Obtain a pointer to either the array's underlying storage, or a copy of
    // the storage.
    elements = environment->GetByteArrayElements(array, NULL);
    if(elements == NULL)
        return NULL;

    // Set the array elements.
    memcpy(elements, buffer, length);

    // Update the Java array with the new elements.
    environment->ReleaseByteArrayElements(array, elements, 0);

    return array;
}

jobject java_encode(const char *string)
{
    return java_encode(string, strlen(string));
}

bool java_decode(jobject byte_array, size_t &length, char *buffer)
{
    JNIEnv              *environment;
    jbyte               *elements;
    jint                jni_result;

    // Obtain a pointer to the JNI environment for the current thread.
    jni_result = virtual_machine->GetEnv((void**)&environment, JNI_REQUIRED);
    if(jni_result != JNI_OK)
        return false;

    // Retrieve the array length.
    length = environment->GetArrayLength((jarray)byte_array);

    // Obtain a pointer to the array storage, or a copy thereof.
    elements = environment->GetByteArrayElements((jbyteArray)byte_array, NULL);
    if(elements == NULL)
        return false;

    // Copy array contents from the array storage.
    memcpy(buffer, elements, length);

    // Release the array storage. If a copy was made, the copy will be
    // deallocated.
    environment->ReleaseByteArrayElements((jbyteArray)byte_array, elements,
                                          JNI_ABORT);

    return true;
}

char* java_decode(jobject byte_array, size_t &length)
{
    JNIEnv              *environment;
    char                *result;
    jint                jni_result;

    // Obtain a pointer to the JNI environment for the current thread.
    jni_result = virtual_machine->GetEnv((void**)&environment, JNI_REQUIRED);
    if(jni_result != JNI_OK)
        return NULL;

    // Retrieve the array length.
    length = environment->GetArrayLength((jarray)byte_array);

    // Allocate space for a copy of the array contents.
    result = new char[length];
    if(result == NULL)
        return NULL;

    // Retrieve the array contents and store them in the newly-allocated buffer.
    if(!java_decode(byte_array, length, result))
    {
        delete[] result;
        return NULL;
    }

    return result;
}
//@}

/** @name Method Calls

    The template functions in this section must be explicitly instantiated at
    each Java type for them to be usable at that type. Failure to do so results
    in a linker error. This is deliberate - it is one (but not the best) way to
    prevent the templates from being implicitly instantiated by the compiler at
    a non-Java type.
 */
//@{
// If the generic version of java_call is rewritten to throw exceptions on
// error, then it is possible for the result of the Java call to be returned as
// the result of the C++ call to java_call. In this case, the two java_call
// functions - one for Java methods returning void, and one for all other Java
// methods - can be combined into one template function.
bool java_call(const char *method, const char *signature, jthrowable &exception,
               ...)
{
    JNIEnv              *environment;
    jclass              cls;
    jmethodID           method_id;
    va_list             arguments;
    jint                jni_result;

    // Retrieve a pointer to the JNI environment for the current thread.
    jni_result = virtual_machine->GetEnv((void**)&environment, JNI_REQUIRED);
    if(jni_result != JNI_OK)
        return false;

    // Find the fuse.Fuse class.
    cls = environment->FindClass(CLASS_NAME);
    if(cls == NULL)
    {
        java_record_exception(environment, exception);
        return false;
    }

    // Retrieve the ID for the method to be called.
    method_id = environment->GetStaticMethodID(cls, method, signature);
    if(method_id == NULL)
    {
        java_record_exception(environment, exception);
        return false;
    }

    va_start(arguments, exception);

    // Call the method, expecting no return value.
    environment->CallStaticVoidMethodV(cls, method_id, arguments);
    java_record_exception(environment, exception);

    return true;
}

/** @brief Calls a static Java method with the specified return type.

    This function in fact does nothing. Its primary purpose is to be a generic
    template for specific explicit instantiations at different Java types. For
    example, the instantiation for the Java type <code>jboolean</code> should
    call the JNI function <code>CallStaticBooleanMethodV</code> to perform the
    method call.

    The template can be instantiated for <code>jboolean</code>,
    <code>jchar</code>, <code>jbyte</code>, <code>jshort</code>,
    <code>jint</code>, <code>jlong</code>, <code>jfloat</code>,
    <code>jdouble</code>, and <code>jobject</code>. Currently, instantiations
    for <code>jboolean</code>, <code>jlong</code>, and <code>jobject</code> are
    the only ones provided.

    @tparam JavaType JNI return type of the method.
    @param environment JNI environment for the thread performing the call.
    @param cls Class in which the method is located.
    @param method Method ID for the static method.
    @param arguments List of arguments to be passed to the method.
    @return The result of calling the method. This generic function in fact
            returns <code>NULL</code>.
 */
template <typename JavaType>
    static JavaType java_call_static_method(JNIEnv *environment, jclass cls,
                                            jmethodID method, va_list arguments)
{
    // This should cause an error if the generic template is ever implicitly
    // instantiated.
    return (void)NULL;
}

/** @brief Calls a static Java method returning a <code>boolean</code>.

    @param environment JNI environment for the thread performing the call.
    @param cls Class in which the method is located.
    @param method Method ID for the static method.
    @param arguments List of arguments to be passed to the method.
    @return The result of calling the method.
 */
template <>
    jboolean java_call_static_method(JNIEnv *environment, jclass cls,
                                     jmethodID method, va_list arguments)
{
    return environment->CallStaticBooleanMethodV(cls, method, arguments);
}

/** @brief Calls a static Java method returning a <code>long</code>.

    @param environment JNI environment for the thread performing the call.
    @param cls Class in which the method is located.
    @param method Method ID for the static method.
    @param arguments List of arguments to be passed to the method.
    @return The result of calling the method.
 */
template <>
    jlong java_call_static_method(JNIEnv *environment, jclass cls,
                                  jmethodID method, va_list arguments)
{
    return environment->CallStaticLongMethodV(cls, method, arguments);
}

/** @brief Calls a static Java method returning an object (this includes
           arrays).

    @param environment JNI environment for the thread performing the call.
    @param cls Class in which the method is located.
    @param method Method ID for the static method.
    @param arguments List of arguments to be passed to the method.
    @return The result of calling the method.
 */
template <>
    jobject java_call_static_method(JNIEnv *environment, jclass cls,
                                    jmethodID method, va_list arguments)
{
    return environment->CallStaticObjectMethodV(cls, method, arguments);
}

template <typename JavaType>
    bool java_call(const char *method, const char *signature, JavaType &result,
                   jthrowable &exception, ...)
{
    JNIEnv              *environment;
    jclass              cls;
    jmethodID           method_id;
    va_list             arguments;
    jint                jni_result;

    // Retrieve the JNI environment for the current thread.
    jni_result = virtual_machine->GetEnv((void**)&environment, JNI_REQUIRED);
    if(jni_result != JNI_OK)
        return false;

    // Find the fuse.Fuse class.
    cls = environment->FindClass(CLASS_NAME);
    if(cls == NULL)
    {
        java_record_exception(environment, exception);
        return false;
    }

    // Retrieve the method ID for the method to be called.
    method_id = environment->GetStaticMethodID(cls, method, signature);
    if(method_id == NULL)
    {
        java_record_exception(environment, exception);
        return false;
    }

    va_start(arguments, exception);

    // Call the method, note any exception, and return the result.
    result = java_call_static_method<JavaType>(environment, cls, method_id,
                                               arguments);
    java_record_exception(environment, exception);

    return true;
}

/** @brief Calls a static Java method returning <code>boolean</code> in the Java
           class <code>fuse.Fuse</code>.

    This function is an explicit instantiation of <code>java_call</code> at
    <code>jboolean</code>.

    @param method Method name.
    @param signature Method signature.
    @param result Reference to a location that will be filled with the result of
                  the method call, if the method call is successful and an
                  exception does not occur.
    @param exception Reference to a location that will be filled with a Java
                     exception object if an exception occurs while attempting to
                     call the method, or while executing the method body. If no
                     exception occurs, the referent is set to <code>NULL</code>.
    @param ... Arguments to be passed to the method.
    @return <code>true</code> if the method was found and called,
            <code>false</code> otherwise.
 */
template bool java_call<jboolean>(const char *method, const char *signature,
                                  jboolean &result, jthrowable &exception, ...);

/** @brief Calls a static Java method returning <code>long</code> in the Java
           class <code>fuse.Fuse</code>.

    This function is an explicit instantiation of <code>java_call</code> at
    <code>jlong</code>.

    @param method Method name.
    @param signature Method signature.
    @param result Reference to a location that will be filled with the result of
                  the method call, if the method call is successful and an
                  exception does not occur.
    @param exception Reference to a location that will be filled with a Java
                     exception object if an exception occurs while attempting to
                     call the method, or while executing the method body. If no
                     exception occurs, the referent is set to <code>NULL</code>.
    @param ... Arguments to be passed to the method.
    @return <code>true</code> if the method was found and called,
            <code>false</code> otherwise.
 */
template bool java_call<jlong>(const char *method, const char *signature,
                               jlong &result, jthrowable &exception, ...);

/** @brief Calls a static Java method returning an object (or an array) in the
           Java class <code>fuse.Fuse</code>.

    This function is an explicit instantiation of <code>java_call</code> at
    <code>jobject</code>.

    @param method Method name.
    @param signature Method signature.
    @param result Reference to a location that will be filled with the result of
                  the method call, if the method call is successful and an
                  exception does not occur.
    @param exception Reference to a location that will be filled with a Java
                     exception object if an exception occurs while attempting to
                     call the method, or while executing the method body. If no
                     exception occurs, the referent is set to <code>NULL</code>.
    @param ... Arguments to be passed to the method.
    @return <code>true</code> if the method was found and called,
            <code>false</code> otherwise.
 */
template bool java_call<jobject>(const char *method, const char *signature,
                                 jobject &result, jthrowable &exception, ...);
//@}
