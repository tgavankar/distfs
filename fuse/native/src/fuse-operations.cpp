/** @file fuse-operations.cpp
    @brief FUSE operations - FUSE library interface.

    This file implements the functions expected by the FUSE interface, and
    necessary for a minimally-useful filesystem.
 */

// It would be preferable to include cstdint, but support for this header seems
// to be flaky.
#include <climits>
#include <cstddef>
#include <cstring>
#include <cstdio>
#include <cerrno>
#include <pthread.h>
#include <sys/statvfs.h>
#include "common.h"
#include <jni.h>
#include "java.h"
#include <fuse.h>
#include "fuse-operations.h"

/** @name Success Code */
//@{
/// Value returned by FUSE interface functions when there is no error.
#define SUCCESS             0
//@}

/** @name Command Line Options */
//@{
/// Mask defining which bits are used for file permissions. This is used to
/// sanitize permissions given by the user on the command line.
#define PERMISSION_MASK     0777

/// FUSE driver filesystem-specific options.
struct option_list
{
    /// Naming server hostname.
    const char  *hostname;
    /// Permissions used for all files.
    mode_t      file_mode;
    /// Permissions used for all directories.
    mode_t      directory_mode;
    /// Name of file to be used for logging.
    const char  *log_file;
}
    /** @brief Options structure. This is initialized with the default values of
        the options.

        @see parse_options
     */
    options =
        {"127.0.0.1", 0644, 0755, NULL};

/** @brief Option pattern list passed to <code>fuse_opt_parse</code>.

    See the FUSE documentation for a description of this structure.
 */
fuse_opt option_patterns[] =
    {{"--server=%s",         offsetof(option_list, hostname),       0},
     {"--file-mode=%o",      offsetof(option_list, file_mode),      0},
     {"--directory-mode=%o", offsetof(option_list, directory_mode), 0},
     {"--error-log=%s",      offsetof(option_list, log_file),       0},
     {NULL,                  -1,                                    0}};

bool parse_options(int &argc, char **&argv)
{
    // Declare and initialzie the fuse_args structure.
    fuse_args   arguments = FUSE_ARGS_INIT(argc, argv);
    bool        result;

    // Parse the filesystem-specific command line arguments.
    result =
        (fuse_opt_parse(&arguments, &options,
                        option_patterns, NULL) == SUCCESS);

    // Parsing the command line arguments has cased FUSE to modify argc and
    // argv. Make sure that the caller's copies of these variables are also
    // modified. This should be safe, as this is simple modification of
    // pointers in automatic storage in main. Technically, the arguments
    // structure should be deallocated when the process terminates. However,
    // that would complicate the code somewhat, with minimal benefit, as the
    // memory will be reclaimed by the system anyway, and no other system
    // resources are affected.
    argc = arguments.argc;
    argv = arguments.argv;

    // Sanitize the file mode and directory mode values given by the user. The
    // user should be unable to, for instance, set S_IFREG on directories by
    // specifying the flag on the command line.
    options.file_mode &= PERMISSION_MASK;
    options.directory_mode &= PERMISSION_MASK;

    return result;
}
//@}

/** @name Error Reporting and Logging

    The convenience macros in this section that take a variable number of
    arguments make use of a GCC extension that permits them to pass on those
    arguments to a variable-argument function even when the argument list is
    empty. This GCC extension automatically removes the comma immediately
    following the last fixed argument.
 */
//@{
/// Mutex to prevent simultaneous writing to the log by concurrent threads.
pthread_mutex_t     log_lock;

static bool log_initialize(void);
static void log_write_real(const char *format, ...);
static void log_exception_real(jthrowable exception, bool stack_trace);

/** @brief Initializes logging.

    This function opens the log file for writing, causing it to be truncated. As
    a side effect, it also checks that the FUSE daemon process has write access
    to the log file. The function has no effect if logging is not enabled.

    This function should be called from <code>dfs_init</code>, before multiple
    threads attempt to write to the log file.

    @return <code>true</code> if logging has been initialized successfully,
            <code>false</code> otherwise.
 */
static bool log_initialize()
{
    if(options.log_file == NULL)
        return true;

    // Initialize the log file lock.
    if(pthread_mutex_init(&log_lock, NULL) != SUCCESS)
        return false;

    // Open the log file for writing and truncate it.
    FILE            *log_file = fopen(options.log_file, "w");
    if(log_file == NULL)
        return false;

    fclose(log_file);

    return true;
}

/** @brief Writes a message to the log file.

    This function has no effect if the logging is not enabled.

    @param format Message format. This is the same as for <code>printf</code>.
    @param ... Additional message arguments. The types and order of these are
               determined by the format string.
 */
void log_write_real(const char *format, ...)
{
    if(options.log_file == NULL)
        return;

    // Lock the log file to ensure other threads will not write to it.
    if(pthread_mutex_lock(&log_lock) != SUCCESS)
        return;

    // Open the log file in append mode.
    FILE            *log_file = fopen(options.log_file, "a");
    if(log_file == NULL)
    {
        pthread_mutex_unlock(&log_lock);
        return;
    }

    // Write the message to the log file and close it.
    va_list         arguments;

    va_start(arguments, format);
    vfprintf(log_file, format, arguments);

    fclose(log_file);

    // Release the lock on the log file.
    pthread_mutex_unlock(&log_lock);
}

/** @brief Writes a report derived from a Java exception to the log file.

    This function has no effect if the logging is not enabled.

    @param exception Exception to be formatted.
    @param stack_trace If <code>true</code>, a stack trace will be printed to
                       the log file. Otherwise, the message printed will be the
                       same as that returned by the exception's
                       <code>toString</code> method.
 */
static void log_exception_real(jthrowable exception, bool stack_trace)
{
    if(options.log_file == NULL)
        return;

    // Lock the log file to prevent writing by other threads and write the
    // exception.
    if(pthread_mutex_lock(&log_lock) != SUCCESS)
        return;

    java_describe_exception(exception, options.log_file, stack_trace);

    pthread_mutex_unlock(&log_lock);
}

/// Log message indicating that the Java virtual machine could not be
/// initialized.
#define CANNOT_INITVM    "cannot initialize Java virtual machine."
/// Log message indicating a thread failed to attach to the Java virtual
/// machine.
#define CANNOT_ATTACH    "cannot attach current thread to Java virtual machine."
/// Log message indicating that at least one Java class could not be loaded.
#define CANNOT_LOAD      "cannot load one more Java classes."
/// Log message indicating that filesystem initialization failed.
#define CANNOT_INITFS    "cannot initialize filesystem."
/** @brief Log message indicating that a buffer could not be converted to a Java
           byte array.

    @param name Source code variable name of the buffer, unquoted. This should
                simply be the name of the variable that holds a poiner to the
                buffer in question.
 */
#define CANNOT_ENCODE(name) "cannot to convert " #name " to a Java byte array."
/** @brief Log message indicating that a Java byte array could not be converted
           to a native buffer.

    @param name Source code variable name of the Java byte array, unquoted.
 */
#define CANNOT_DECODE(name) "cannot convert " #name " to a native byte buffer."
/** @brief Log message indicating that a static method of the class
           <code>fuse.Fuse</code> could not be called.

    @param name Name of the method. This should be a literal string.
 */
#define CANNOT_CALL(name)    "cannot call Java fuse.Fuse." name " method."
/// Log message indicating that a file or directory could not be deleted.
#define CANNOT_DELETE    "cannot delete file or directory."
/// Log message indicating that a path does not refer to a directory.
#define CANNOT_OPENDIR   "cannot open: path does not refer to a directory."

/** @brief Convenience macro for writing to the log file.

    The macro inserts the name of the calling function into the message written,
    and terminates the message with a newline character.

    @param format Format of the message to be written.
    @param ... Additional values to be formatted, as per the format string.
 */
#define log_write(format, ...) \
    log_write_real("%s: " format "\n", __func__, ##__VA_ARGS__)

/** @brief Convenience macro for writing to the log file and detaching from the
           Java virtual machine.

    The macro behaves as <code>log_write</code>, but also calls
    <code>java_detach</code>. The whole macro expands to a single expression
    (and not a statement). The type of the expression is <code>void</code>,
    because this is the type of <code>java_detach</code>.

    @param format Format of the message to be written.
    @param ... Additional values to be formatted, as per the format string.
 */
#define log_and_detach(format, ...) \
    log_write_real("%s: " format "\n", __func__, ##__VA_ARGS__), java_detach()

/** @brief Convenience macro for writing to the log file and returning a POSIX
           error code.

    The macro inserts the name of the calling function and the <em>name</em> of
    the error code (such as <code>EINVAL</code> or <code>EIO</code> into the
    message written - provided the textual macro form of the error code is
    provided. It also terminates the message with a newline.

    This macro expands to a statement. Specifically, it is a compound statement,
    the last component of which returns the negated error code, as per the FUSE
    specification.

    @param error Error code. Ideally, this is a macro such as
                 <code>EINVAL</code> or <code>EIO</code>, and not an integer
                 literal.
    @param format Format of the message to be written.
    @param ... Additional values to be formatted, as per the format string.
 */
#define log_error_and_return(error, format, ...)                               \
    {                                                                          \
        log_write_real("%s: " #error ": " format "\n",                         \
                       __func__, ##__VA_ARGS__);                               \
        return -error;                                                         \
    }

/** @brief Convenience macro for writing to the log file, detaching from the
           Java virtual machine, and returning a POSIX error code.

    The macro behaves as <code>log_and_return</code>, but also calls
    <code>java_detach</code> immediately before returning.

    @param error Error code. Ideally, this is a macro such as
                 <code>EINVAL</code> or <code>EIO</code>, and not an integer
                 literal.
    @param format Format of the message to be written.
    @param ... Additional values to be formatted, as per the format string.
 */
#define log_error_detach_and_return(error, format, ...)                        \
    {                                                                          \
        log_write_real("%s: " #error ": " format "\n",                         \
                       __func__, ##__VA_ARGS__);                               \
        java_detach();                                                         \
        return -error;                                                         \
    }

/** @brief Convenience macro for logging a Java exception, detaching from the
           Java virtual machine, and returning the exception's POSIX error code.

    The macro prints the name of the calling function, and then prints a short
    summary of the exception (equivalent to the one returned by the exception's
    <code>toString</code> method). It then detaches the current thread from the
    virtual machine, and returns the negated POSIX error code corresponding to
    the exception.

    This macro expands to a compound statement.

    @param exception The Java exception.
 */
#define log_exception_detach_and_return(exception)                             \
    {                                                                          \
        int     error_code = java_error_code(exception);                       \
        log_write_real("%s: ", __func__);                                      \
        log_exception_real(exception, false);                                  \
        java_detach();                                                         \
        return -error_code;                                                    \
    }

/** @brief Convenience macro for detaching from the Java virtual machine and
           returning an error code.

    This macro expands to a compound statement.

    @param error Error code to return.
 */
#define detach_and_return(error)                                               \
    {                                                                          \
        java_detach();                                                         \
        return -error;                                                                 \
    }
//@}

/** @name FUSE Operations

    Unless otherwise stated, the functions return POSIX error codes as follows:

    - <code>SUCCESS</code> (zero) if no error occurs.
    - <code>EINVAL</code> is returned if the path given is not a valid
      filesystem path.
    - <code>EACCES</code> is returned if the path refers to an object that
      cannot be accessed according to the type of operation requested and the
      permissions on filesystem objects. For all objects other than the root
      directory, performing any operation requires directories to have the
      traverse (execute) bit set.
    - <code>ENOENT</code> is returned if the path does not refer to an existing
      filesystem object, refers to an object of the wrong type, or the operation
      would modify a directory, but the directory to be modified does not exist.

    Where functions return additional error codes, or do not return one of these
    error codes, additional documentation is provided.

    Error codes occasionally have unexpected precedence: for instance, the
    creation functions <code>dfs_mknod</code> and <code>dfs_mkdir</code> may be
    asked to create a file (or directory) in a directory that does not exist. It
    might also be the case that even if the directory does exist, access would
    not be allowed, because the directory is not writable (but is traversible).
    In this case, the functions should return <code>ENOENT</code>. They will,
    however, return <code>EACCES</code>, because the access check is easy to
    perform, while the existence check requires communication with the naming
    server. Traversal checks, if they are performed at all, are performed first,
    however.
 */
//@{
static int dfs_getattr(const char *path, struct stat *status);
static int dfs_mknod(const char *path, mode_t mode, dev_t device);
static int dfs_mkdir(const char *path, mode_t mode);
static int dfs_delete(const char *path);
static int dfs_truncate(const char *path, off_t new_size);
static int dfs_open(const char *path, fuse_file_info *file_info);
static int dfs_read(const char *path, char *buffer, size_t length, off_t offset,
                    fuse_file_info *file_info);
static int dfs_write(const char *path, const char *buffer, size_t length,
                     off_t offset, fuse_file_info *file_info);
static int dfs_statfs(const char *path, struct statvfs *statistics);
static int dfs_flush(const char *path, fuse_file_info *file_info);
static int dfs_release(const char *path, fuse_file_info *file_info);
static int dfs_fsync(const char *path, int datasync, fuse_file_info *file_info);
static int dfs_opendir(const char *path, fuse_file_info *file_info);
static int dfs_readdir(const char *path, void *buffer, fuse_fill_dir_t fill,
                       off_t offset, fuse_file_info *file_info);
static int dfs_releasedir(const char *path, fuse_file_info *file_info);
static int dfs_fsyncdir(const char *path, int datasync,
                        fuse_file_info *file_info);
static int dfs_access(const char *path, int mode);
static void* dfs_init(fuse_conn_info *connection_info);
static void dfs_destroy(void *opaque);
static bool dfs_is_root(const char *path);
static int dfs_may_access(int mode, int request);
static bool dfs_listing_allowed(void);
static bool dfs_directory_modification_allowed(void);
static bool dfs_traversals_allowed(void);

void set_operations(fuse_operations& operations)
{
    // Set all members to NULL.
    memset(&operations, 0, sizeof(fuse_operations));

    // Set members corresponding to implemented functions to point to the
    // functions themselves.
    operations.getattr = dfs_getattr;
    operations.mknod = dfs_mknod;
    operations.mkdir = dfs_mkdir;
    operations.unlink = dfs_delete;
    operations.rmdir = dfs_delete;
    operations.truncate = dfs_truncate;
    operations.open = dfs_open;
    operations.read = dfs_read;
    operations.write = dfs_write;
    operations.statfs = dfs_statfs;
    operations.flush = dfs_flush;
    operations.release = dfs_release;
    operations.fsync = dfs_fsync;
    operations.opendir = dfs_opendir;
    operations.readdir = dfs_readdir;
    operations.releasedir = dfs_releasedir;
    operations.fsyncdir = dfs_fsyncdir;
    operations.access = dfs_access;
    operations.init = dfs_init;
    operations.destroy = dfs_destroy;
}

/** @brief Convenience macro for attaching a thread to the Java virtual machine.

    If the operation fails, the macro causes the function using it to write a
    message to the log and return <code>EIO</code>.
 */
#define try_attach()                                                           \
    if(!java_attach())                                                         \
        log_error_and_return(EIO, CANNOT_ATTACH);

/** @brief Convenience macro for converting a string to a Java byte array.

    If the operation fails, the macro causes the function using to to write a
    message to the log and return <code>EIO</code>.

    @param source Pointer to the native string to be converted.
    @param result Name of a variable or other memory location of type
                  <code>jobject</code> that will be set to the result of the
                  operation.
 */
#define try_encode(source, result)                                             \
    result = java_encode(source);                                              \
    if(result == NULL)                                                         \
        log_error_detach_and_return(EIO, CANNOT_ENCODE(source));

/** @brief Convenience macro for calling a static Java method with
           non-<code>void</code> return type in the class
           <code>fuse.Fuse</code>.

    If the attempted call results in an exception, whether from the call attempt
    or due to execution of method code, this macro writes the exception to the
    log, and causes the using method to return the POSIX error code
    corresponding to the exception. If the call attempt fails and no exception
    is thrown, the macro writes an error message to the log and causes the using
    method to return <code>EIO</code>.

    @param method Native string giving the method name.
    @param signature Native string giving the method type signature.
    @param result Name of a variable that will be set to the result of the call,
                  if the call succeeds.
 */
#define try_call(method, signature, result, ...)                               \
    {                                                                          \
        jthrowable  exception;                                                 \
        bool        success =                                                  \
            java_call(method, signature, result, exception, ##__VA_ARGS__);    \
        if(exception != NULL)                                                  \
            log_exception_detach_and_return(exception);                        \
        if(!success)                                                           \
            log_error_detach_and_return(EIO, CANNOT_CALL(method));             \
    }

/** @brief Convenience macro for calling a static Java method with
           <code>void</code> return type in the class <code>fuse.Fuse</code>.

    This macro behaves as the <code>try_call</code> macro, except that no result
    is stored as the Java method does not return a result.

    @param method Native string giving the method name.
    @param signature Native string giving the method type signature.
 */
#define try_void_call(method, signature, ...)                                  \
    {                                                                          \
        jthrowable  exception;                                                 \
        bool        success =                                                  \
            java_call(method, signature, exception, ##__VA_ARGS__);            \
        if(exception != NULL)                                                  \
            log_exception_detach_and_return(exception);                        \
        if(!success)                                                           \
            log_error_detach_and_return(EIO, CANNOT_CALL(method));             \
    }

/** @brief Obtains file or directory attributes.

    Attributes returned are the object type (file or directory), permissions
    (determined by command line arguments at mount-time), and, for files, file
    size.

    @param path Path to the object whose attributes are to be retrieved.
    @param status Pointer to a <code>stat</code> structure to be filled with the
                  attributes. Attributes not set by this function are set to
                  zero.
    @return A POSIX error code as described in the group documentation for FUSE
            operations.
 */
static int dfs_getattr(const char *path, struct stat *status)
{
    // Unless this is the root directory, access is not allowed unless
    // directories can be traversed to this object.
    if(!dfs_is_root(path))
    {
        if(!dfs_traversals_allowed())
            return -EACCES;
    }

    jobject     java_path;
    jboolean    directory;

    // Attach this thread to the virtual machine and check if the path refers to
    // a directory. If the method call returns, the path definitely refers to an
    // existing object - the only question then is what to do with it.
    try_attach();
    try_encode(path, java_path);
    try_call("directory", "([B)Z", directory, java_path);

    // Clear the status structure.
    memset(status, 0, sizeof(status));

    // If the object is a directory, set the mode field appropriately.
    // Otherwise, the object is a file. Perform a second Java call to retrieve
    // the file size from one of the storage servers, and set the stat structure
    // fields appropriately.
    if(directory == JNI_TRUE)
        status->st_mode = S_IFDIR | options.directory_mode;
    else
    {
        jlong   size;

        try_call("size", "([B)J", size, java_path);

        status->st_mode = S_IFREG | options.file_mode;
        status->st_size = size;
    }

    status->st_nlink = 1;

    detach_and_return(SUCCESS);
}

/** @brief Creates a file on the filesystem.

    @param path Path to the file to be created.
    @param mode File mode. This parameter is ignored, as the filesystem is not
                capable of storing file modes.
    @param device Ignored.
    @return A POSIX error code as described in the group documentation for FUSE
            operations. In addition, the function returns <code>EEXIST</code>
            if the file could not be created because an object with the given
            path already exists.
 */
static int dfs_mknod(const char *path, mode_t mode, dev_t device)
{
    // If the path refers to the root directory, return EEXIST.
    if(dfs_is_root(path))
        return -EEXIST;

    // The path does not refer to the root directory. A file with the given path
    // cannot be created if the root directory cannot be traversed.
    if(!dfs_traversals_allowed())
        return -EACCES;

    // No files can be created if directory modification is not allowed.
    if(!dfs_directory_modification_allowed())
        return -EACCES;

    jobject     java_path;
    jboolean    result;

    // Attempt to create the file.
    try_attach();
    try_encode(path, java_path);
    try_call("createFile", "([B)Z", result, java_path);
    java_detach();

    if(result != JNI_TRUE)
        return -EEXIST;

    return SUCCESS;
}

/** @brief Creates a directory on the filesystem.

    @param path Path to the directory to be created.
    @param mode Directory mode. This parameter is ignored, as the filesystem is
                not capable of storing directory modes.
    @return A POSIX error code as described in the group documentation for FUSE
            operations. In addition, the function returns <code>EEXIST</code>
            if the directory could not be created because an object with the
            given path already exists.
 */
static int dfs_mkdir(const char *path, mode_t mode)
{
    // If the path refers to the root directory, return EEXIST.
    if(dfs_is_root(path))
        return -EEXIST;

    // The path does not refer to the root directory. A directory with the given
    // path cannot be created if the root directory cannot be traversed.
    if(!dfs_traversals_allowed())
        return -EACCES;

    // No directories can be created if directory modification is not allowed.
    if(!dfs_directory_modification_allowed())
        return -EACCES;

    jobject     java_path;
    jboolean    result;

    // Attempt to create the directory.
    try_attach();
    try_encode(path, java_path);
    try_call("createDirectory", "([B)Z", result, java_path);
    java_detach();

    if(result != JNI_TRUE)
        return -EEXIST;

    return SUCCESS;
}

/** @brief Deletes an object in the filesystem.

    @param path Path to the object to be deleted.
    @return A POSIX error code as described in the group documentation for FUSE
            operations. It is not expected that deletion will fail without an
            exception thrown. If this occurs (if the result of calling the
            <code>delete</code> method is <code>false</code>), this function
            will return <code>EPERM</code>. <code>EPERM</code> is also returned
            if the user attempts to delete the root directory.
 */
static int dfs_delete(const char *path)
{
    // The root directory cannot be deleted. This check is performed by the
    // naming server as well. However, it is necessary to do it in the client
    // anyway as part of traversal checking.
    if(dfs_is_root(path))
        return -EPERM;

    // The path does not refer to the root directory. Return EACCES if the root
    // directory cannot be traversed.
    if(!dfs_traversals_allowed())
        return -EACCES;

    // Objects cannot be deleted if directories are not modifiable.
    if(!dfs_directory_modification_allowed())
        return -EACCES;

    jobject     java_path;
    jboolean    result;

    // Attempt to delete the object.
    try_attach();
    try_encode(path, java_path);
    try_call("delete", "([B)Z", result, java_path);

    if(result != JNI_TRUE)
        log_error_detach_and_return(EPERM, CANNOT_DELETE);

    detach_and_return(SUCCESS);
}

/** @brief Truncates a file to length zero.

    This is only a partial implementation of the <code>truncate</code> function.
    It is not possible to use this function to set the file size to anything
    except zero. This is, however, the common case. The purpose of this partial
    implementation is to allow files to be cleared when they are opened.
    Truncation is not atomic - it is implemented as three calls to filesystem
    methods. Once the object is known to be a file and not a directory, it is
    deleted and then re-created. Race conditions with other clients are very
    much possible.

    @param path Path to the file to be truncated.
    @param new_size New size of the file. Must be zero for the operation to
                    work.
    @return A POSIX error code as described in the group documentation for FUSE
            operations. In addition, if <code>new_size</code> is not zero, the
            function returns <code>ENOTSUP</code>. If the path refers to a
            directory, the function returns <code>EISDIR</code>. Finally, if the
            file is deleted, but cannot be re-created due to a race condition
            (another client has created a file or directory with the given
            name), the function returns <code>ECANCELED</code>.
 */
static int dfs_truncate(const char *path, off_t new_size)
{
    // Make sure that the new size is zero.
    if(new_size != 0)
        return -ENOTSUP;

    // Requests to truncate the root directory would be caught by the Java
    // method call. However, this check is needed here anyway as part of
    // traversal checking.
    if(dfs_is_root(path))
        return -EISDIR;

    // Access is denied if the root directory cannot be traversed.
    if(!dfs_traversals_allowed())
        return -EACCES;

    jobject     java_path;
    jboolean    directory;
    jboolean    result;

    try_attach();
    try_encode(path, java_path);

    // Check if the path refers to a directory.
    try_call("directory", "([B)Z", directory, java_path);
    if(directory == JNI_TRUE)
        detach_and_return(EISDIR);

    // If not, make sure that files are writable.
    if(dfs_may_access(options.file_mode, W_OK) != SUCCESS)
        detach_and_return(EACCES);

    // If so, delete and attempt to re-create the file.
    try_call("delete", "([B)Z", result, java_path);
    if(result != JNI_TRUE)
        log_error_detach_and_return(EPERM, CANNOT_DELETE);

    try_call("createFile", "([B)Z", result, java_path);
    java_detach();

    if(result != JNI_TRUE)
        return -ECANCELED;

    return SUCCESS;
}

/** @brief Opens a file on the filesystem.

    This function has two primary purposes: to perform access checks, and to
    retrieve the size of the file. The size of the file is needed for later
    <code>read</code> calls, as attempting to read beyond the end of file will
    result in exceptions. The client must be aware of the size of the file to
    prevent this. Exceptions may still be generated, however, because nothing in
    the driver or filesystem implementation prevents another client (or even the
    same client) from truncating the file while it is open for reading.

    @param path Path to the file to be opened.
    @param file_info Structure to be filled with file-related information.
    @return A POSIX error code as described in the group documentation for FUSE
            operations. In addition, this function returns <code>ENOTSUP</code>
            if the open request includes the <code>O_EXCL</code> flag. The
            driver explicitly does not support <code>O_EXCL</code>, as processes
            should be aware that they will not be able to use this for
            synchronization or atomic operatons.
 */
static int dfs_open(const char *path, fuse_file_info *file_info)
{
    // Perform the traversal check.
    if(dfs_is_root(path))
        return -ENOENT;

    if(!dfs_traversals_allowed())
        return -EACCES;

    jobject     java_path;
    jlong       size;

    // Retrieve the file size.
    try_attach();
    try_encode(path, java_path);
    try_call("size", "([B)J", size, java_path);
    java_detach();

    // Check that file permissions allow the file to be opened for the type of
    // access requested.
    if((file_info->flags & O_RDWR) == O_RDWR)
    {
        if(dfs_may_access(options.file_mode, R_OK | W_OK) != SUCCESS)
            return -EACCES;
    }

    if((file_info->flags & O_RDONLY) == O_RDONLY)
    {
        if(dfs_may_access(options.file_mode, R_OK) != SUCCESS)
            return -EACCES;
    }

    if((file_info->flags & O_WRONLY) == O_WRONLY)
    {
        if(dfs_may_access(options.file_mode, W_OK) != SUCCESS)
            return -EACCES;
    }

    // Do not allow the file to be opened if the O_EXCL flag is specified.
    if((file_info->flags & O_EXCL) != 0)
        return -ENOTSUP;

    // Store the file's size as the "file handle" and return.
    file_info->fh = size;

    return SUCCESS;
}

/** @brief Reads from an open file.

    No existence or access checks are performed on the path, as this function
    should only be called for open files, and the checks are performed in
    <code>dfs_open</code>.

    @param path Path to the file to be read.
    @param buffer Buffer to be filled with data from the file.
    @param length Number of bytes to read from the file.
    @param offset Offset into the file from which the bytes should be read.
    @param file_info File information, including the file handle.
    @return The number of bytes read, or zero if the end of file is reached.
            On error, the function returns POSIX error codes as described in the
            group documentation for FUSE operations.
 */
static int dfs_read(const char *path, char *buffer, size_t length, off_t offset,
                    fuse_file_info *file_info)
{
    jint        java_length;
    jlong       java_offset;

    // If the length requested is greater that the maximum value of a signed
    // 32-bit integer, truncate it. The call will fail anyway (the virtual
    // machine will not have sufficient heap space). However, if the integer is
    // not truncated, it will be taken modulo 2^32 when passed to the Java
    // method, resulting in potentially erroneous behavior when an insufficient
    // number bytes is read from file.
    if(length > INT_MAX)
        java_length = INT_MAX;
    else
        java_length = length;

    // If the offset exceeds the maximum value of a signed 64-bit integer, the
    // request is for bytes that lie beyond the end of file. Clear the output
    // buffer and return zero immediately.
    if(offset > LLONG_MAX)
    {
        memset(buffer, 0, length);
        return 0;
    }
    else
        java_offset = offset;

    jobject     java_path;
    jobject     received_buffer;

    // Attempt to read bytes from the file.
    try_attach();
    try_encode(path, java_path);
    try_call("read", "([BJIJ)[B", received_buffer, java_path, java_offset,
             java_length, file_info->fh);

    // Extract bytes from the array received from the remote server into the
    // native buffer provided as argument.
    size_t      received_length;
    if(!java_decode(received_buffer, received_length, buffer))
        log_error_detach_and_return(EIO, CANNOT_DECODE(received_buffer));

    // Clear the rest of the bytes in the buffer.
    memset(buffer + received_length, 0, length - received_length);

    java_detach();
    return received_length;
}

/** @brief Writes to an open file.

    No existence or access checks are performed on the path, as this function
    should only be called for open files, and the checks are performed in
    <code>dfs_open</code>.

    @param path Path to the file to be written to.
    @param buffer Buffer containing bytes to be written.
    @param length Number of bytes to be written.
    @param offset Offset into the file at which the bytes are to be written.
    @param file_info File information structure. This parameter is not used.
    @return The number of bytes written, or a POSIX error code as described in
            the group documentation for FUSE operations. In addition, if the
            offset of any of the bytes to be written would exceed the maximum
            length of a file in the filesystem, the function returns
            <code>EFBIG</code>.
 */
static int dfs_write(const char *path, const char *buffer, size_t length,
                     off_t offset, fuse_file_info *file_info)
{
    // If the number of bytes written exceeds the maximum value of a 32-bit
    // signed integer, clamp it to that value. The write would fail as the Java
    // virtual machine cannot have a byte array of this size. The call would
    // fail earlier with EIO, however, when the buffer cannot be converted to a
    // Java byte array.
    if(length > INT_MAX)
        length = INT_MAX;

    // Check that the offset does not exceed the maximum file length and that
    // the byte range does not span this limit.
    if(offset > LLONG_MAX)
        return -EFBIG;

    if(offset + length > LLONG_MAX)
        return -EFBIG;

    jobject     java_path;
    jobject     java_buffer;

    try_attach();
    try_encode(path, java_path);

    // Convert the buffer to a Java byte array.
    java_buffer = java_encode(buffer, length);
    if(java_buffer == NULL)
        log_error_detach_and_return(EIO, CANNOT_ENCODE(buffer));

    // Write the buffer to file.
    try_void_call("write", "([BJ[B)V", java_path, (jlong)offset, java_buffer);
    java_detach();

    return length;
}

/** @brief Returns filesystem statistics.

    This function does not perform any access or existence checks on the path,
    as suggested in the standard specification of <code>statvfs</code>. The
    primary purpose of this function is to allow Finder on MacOS X to copy
    files. Finder calls <code>statvfs</code> to make sure there is sufficient
    space on the filesystem before copying a file. Leaving the function
    unimplemented causes the FUSE library to report no free space. The only
    purpose of this function is therefore to report a large amount of free
    space (on the order of 1024GB). The function performs no network
    communication.

    @param path Path to an object on the filesystem. This parameter is ignored.
    @param statistics Structure to be filled with filesystem statistics.
    @return Always returns <code>SUCCESS</code> (zero).
 */
static int dfs_statfs(const char *path, struct statvfs *statistics)
{
    statistics->f_bsize = 0x100000;
    statistics->f_blocks = 0x100000;
    statistics->f_bfree = 0x100000;
    statistics->f_bavail = 0x100000;

    return SUCCESS;
}

/** @brief Flushes updated file contents to the remote filesystem.

    This function in fact does nothing, because the client never caches file
    data. It is provided only for the sake of completeness. No existence or
    access checks are performed on the path, as this function should only be
    called for open files, and the checks are performed in
    <code>dfs_open</code>.

    @param path Path to the file to be flushed. Ignored.
    @param file_info File information. Ignored.
    @return Always returns <code>SUCCESS</code> (zero).
 */
static int dfs_flush(const char *path, fuse_file_info *file_info)
{
    return SUCCESS;
}

/** @brief Closes an open file.

    This function does nothing, and is provided only for the sake of
    completeness. No existence or access checks are performed on the path, as
    this function should only be called for open files, and the checks are
    performed in <code>dfs_open</code>.

    @param path Path to the file to closed. Ignored.
    @param file_info File information. Ignored.
    @return Always returns <code>SUCCESS</code> (zero).
 */
static int dfs_release(const char *path, fuse_file_info *file_info)
{
    return SUCCESS;
}

/** @brief Synchronizes file data.

    This function does nothing, as file data is not cached by the FUSE client.
    It is provided only for the sake of completeness. No existence or access
    checks are performed on the path, as this function should only be called for
    open files, and the checks are performed in <code>dfs_open</code>.

    @param path Path to the file for which data is to be synchronized. Ignored.
    @param datasync Indicates whether only data or also metadata should be
                    synchronized.
    @param file_info File information. Ignored.
    @return Always returns <code>SUCCESS</code> (zero).
 */
static int dfs_fsync(const char *path, int datasync, fuse_file_info *file_info)
{
    return SUCCESS;
}

/** @brief Opens a directory for listing.

    @param path Path to the directory to be opened.
    @param file_info File information. Ignored.
    @return A POSIX error code as described in the group documentation for FUSE
            operations. In addition, if the path refers to a file, the function
            returns <code>ENOTDIR</code>.
 */
static int dfs_opendir(const char *path, fuse_file_info *file_info)
{
    // Perform the traversal check.
    if(!dfs_is_root(path))
    {
        if(!dfs_traversals_allowed())
            return -EACCES;
    }

    jobject     java_path;
    jboolean    result;

    // Determine whether the path refers to a directory.
    try_attach();
    try_encode(path, java_path);
    try_call("directory", "([B)Z", result, java_path);
    java_detach();

    if(result != JNI_TRUE)
        return -ENOTDIR;

    // If the path refers to a directory, check that directories may be listed.
    if(!dfs_listing_allowed())
        return -EACCES;

    return SUCCESS;
}

/** @brief Lists the contents of a directory.

    The FUSE interface allows partial reads of directory contents, by specifying
    the <code>offset</code> parameter. However, it also allows this function to
    ignore that parameter, and return the full directory contents. This is how
    this function behaves. The reason full contents are always returned is
    because it does not make sense to return partial contents: this would
    require multiple trips, or else the directory entry list would have to be
    cached. If the list is cached, it may quickly become stale anyway. It is
    easier to retrieve the full child list and return it to the caller, than to
    implement such a scheme for no apparent gain.

    @param path Path to the directory to be listed.
    @param buffer Buffer to be filled with the directory contents. The buffer
                  is not accessed directly by this function. Instead it is
                  passed as one of the arguments to <code>fill</code>, which
                  fills it.
    @param fill Callback function to be called for each directory member.
    @param offset Index of the first child to be given to <code>fill</code>.
                  This parameter is ignored.
    @param file_info File information structure. This parameter is ignored.
    @return A POSIX error code as described in the group documentation for FUSE
            operations.
 */
static int dfs_readdir(const char *path, void *buffer, fuse_fill_dir_t fill,
                       off_t offset, fuse_file_info *file_info)
{
    jobject     java_path;
    jobject     encoded_children;

    // Retrieve the directory entry list.
    try_attach();
    try_encode(path, java_path);
    try_call("list", "([B)[B", encoded_children, java_path);

    // Convert the child list from a Java byte array to a native buffer.
    size_t      length;
    char        *children = java_decode(encoded_children, length);
    if(children == NULL)
        log_error_detach_and_return(EIO, CANNOT_DECODE(encoded_children));

    // Fill the caller-supplied buffer with the list of children.
    size_t      list_offset = 0;
    while(list_offset < length)
    {
        if(fill(buffer, children + list_offset, NULL, 0) == 1)
            break;

        list_offset += strlen(children + list_offset) + 1;
    }

    delete[] children;

    detach_and_return(SUCCESS);
}

/** @brief Closes a directory that was opened for listing.

    This function in fact does nothing. It is provided for the sake of
    completeness.

    @param path Path to the directory to be closed. Ignored.
    @param file_info File information. Ignored.
    @return Always returns <code>SUCCESS</code> (zero).
 */
static int dfs_releasedir(const char *path, fuse_file_info *file_info)
{
    return SUCCESS;
}

/** @brief Synchronizes directory contents.

    This function in fact does nothing. It is provided for the sake of
    completeness. The FUSE client does not cache modified directory contents
    locally.

    @param path Path to the directory whose contents are to be synchronized.
                Ignored.
    @param datasync Indicates whether metadata or only regular data should be
                    synchronized.
    @param file_info File information. Ignored.
    @return Always returns <code>SUCCESS</code> (zero).
 */
static int dfs_fsyncdir(const char *path, int datasync,
                        fuse_file_info *file_info)
{
    return SUCCESS;
}

/** @brief Performs an access check on the given file or directory.

    This function is provided to prevent some users, such as Nautilus, from
    considering all files to be executable. This browser apparently calls
    <code>access</code> to determine what permissions are set. If the
    <code>access</code> method is not implemented by the driver, the FUSE
    library apparently indicates that the file is executable. This makes
    Nautilus ask the end user whether the file should be executed - a great
    annoyance, since most files are regular data files.

    All access checks are performed as if the owner is making the access - that
    is, only the <code>S_IRUSR</code>, <code>S_IWUSR</code>, and
    <code>S_IXUSR</code> flags are considered.

    @param path File or directory to be checked.
    @param mode Requested access type or types.
    @return A POSIX error code as described in the group documentation for FUSE
            operations.
 */
static int dfs_access(const char *path, int mode)
{
    if(dfs_is_root(path))
        return dfs_may_access(options.directory_mode, mode);

    // If directories cannot be traversed, no kind of access is allowed to
    // anything.
    if(!dfs_traversals_allowed())
        return -EACCES;

    jboolean    directory;
    jobject     java_path;

    // Check whether an object with the given path exists, and determine whether
    // it is a file or a directory.
    try_attach();
    try_encode(path, java_path);
    try_call("directory", "([B)Z", directory, java_path);
    java_detach();

    // Check the mode (provided at the command line at driver startup) according
    // to whether the object is a file or a directory.
    if(directory == JNI_TRUE)
        return dfs_may_access(options.directory_mode, mode);
    else
        return dfs_may_access(options.file_mode, mode);
}

/** @brief Initializes the filesystem driver.

    This function initializes logging (if enabled), starts a Java virtual
    machine, loads all in-memory classes distributed within the native binary
    image, and calls <code>fuse.Fuse.initialize</code> with the naming server
    hostname supplied at the command line.

    The FUSE library is not clear on how to indicate that initialization has
    failed. For this reason, if initialization fails, the driver is likely to
    continue running, with potentially catastrophic effects (for the driver, at
    least). Some of these effects (unless they are segmentation faults) might be
    visible through the log. If the log is completely empty, it may be that
    logging initialization itself has failed.

    @param connection_info FUSE connection info. Ignored.
    @return A pointer-sized value that the FUSE library will later pass to
            <code>dfs_destroy</code>. This implementation does not make use of
            this facility, so the return value is always <code>NULL</code>.
 */
static void* dfs_init(fuse_conn_info *connection_info)
{
    // Initialize logging.
    if(!log_initialize())
        return NULL;

    // Initialize the Java virtual machine.
    if(!java_initialize())
    {
        log_write(CANNOT_INITVM);
        return NULL;
    }

    // Attach the current thread to the virtual machine for additional
    // initialization.
    if(!java_attach())
    {
        log_write(CANNOT_ATTACH);
        return NULL;
    }

    jthrowable  exception;

    // Load inlined Java classes. Print the exception generated if any class
    // cannot be loaded to the log.
    exception = java_load_classes();
    if(exception != NULL)
    {
        log_exception_real(exception, true);
        log_and_detach(CANNOT_LOAD);
        return NULL;
    }

    // Convert the hostname to a Java byte array.
    jobject     java_hostname = java_encode(options.hostname);
    if(java_hostname == NULL)
    {
        log_and_detach(CANNOT_ENCODE(hostname));
        return NULL;
    }

    // Call the initialize method.
    bool        success =
        java_call("initialize", "([B)V", exception, java_hostname);
    if(exception != NULL)
    {
        log_exception_real(exception, true);
        log_and_detach(CANNOT_INITFS);
        return NULL;
    }
    if(!success)
    {
        log_and_detach(CANNOT_CALL("initialize"));
        return NULL;
    }

    java_detach();
    return NULL;
}

/** @brief Cleans up the filesystem.

    This function destroys the Java virtual machine.

    @param opaque Value returned by <code>dfs_init</code>. This value is
                  ignored.
 */
static void dfs_destroy(void *opaque)
{
    java_destroy();
}

/** @brief Determines whether the given path refers to the root directory.

    @param path Path to be checked.
    @return <code>true</code> if and only if the path refers to the root
            directory.
 */
static bool dfs_is_root(const char *path)
{
    // Path characters may use strange encodings - however, the FUSE library
    // guarantees that paths are null-terminated, and that the regular ASCII
    // forward slash character is used for the path separator. Paths also never
    // contain components such as the current or parent directory. Therefore, it
    // is acceptable to simply compare the given path against the string literal
    // below.
    return (strcmp(path, "/") == 0);
}

/** @brief Determines whether the given permissions allow the requested type of
           access.

    @param mode Permissions. The relevant flags are <code>S_IRUSR</code>,
                <code>S_IWUSR</code>, and <code>S_IXUSR</code>.
    @param request Requested access type. This is some combination of
                   <code>R_OK</code>, <code>W_OK</code>, and <code>X_OK</code>.
    @return <code>SUCCESS</code> if access is permitted, <code>EACCES</code> if
            access is denied. The value of <code>EACCES</code> is negated to
            conform to the FUSE specification for functions returning error
            codes.
 */
static int dfs_may_access(int mode, int request)
{
    if((request & R_OK) && !(mode & S_IRUSR))
        return -EACCES;

    if((request & W_OK) && !(mode & S_IWUSR))
        return -EACCES;

    if((request & X_OK) && !(mode & S_IXUSR))
        return -EACCES;

    return SUCCESS;
}

/** @brief Determines whether directories may be listed.

    @return <code>true</code> if and only if directories may be listed.
 */
static bool dfs_listing_allowed()
{
    return (dfs_may_access(options.directory_mode, R_OK) == SUCCESS);
}

/** @brief Determines whether directories may be modified.

    @return <code>true</code> if and only if directories may be modified.
 */
static bool dfs_directory_modification_allowed()
{
    return (dfs_may_access(options.directory_mode, W_OK) == SUCCESS);
}

/** @brief Determines whether directories may be traversed.

    @return <code>true</code> if and only if directories may be traversed.
 */
static bool dfs_traversals_allowed()
{
    return (dfs_may_access(options.directory_mode, X_OK) == SUCCESS);
}
//@}
