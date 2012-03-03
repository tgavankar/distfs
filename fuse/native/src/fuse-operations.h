/** @file fuse-operations.h
    @brief FUSE operations - interface to the rest of the program.

    This header file lists the functions that are needed by <code>main</code> to
    initialize and start the FUSE driver. The majority of the functions needed
    to implement the FUSE interface itself are static in
    <code>fuse-operations.cpp</code>, and are documented in that file.
 */

#ifndef _FUSE_OPERATIONS_H_
#define _FUSE_OPERATIONS_H_

/** @brief Parses command line arguments and sets filesystem-specific and
           (potentially) common FUSE options accordingly.

    The following filesystem-specific options are recognized:
    - <code>--server=hostname</code>: causes the driver to connect to the naming
       server given by <code>hostname</code>. The default value is
       <code>127.0.0.1</code>.
    - <code>--file-mode=mode</code>: causes the driver to present all files as
      having the mode <code>mode</code>. <code>mode</code> should be a
      three-digit octal number. The default value is <code>644</code>.
    - <code>--directory-mode=mode</code>: causes the driver to present all
      directories as having the mode <code>mode</code>. <code>mode</code> should
      be a three-digit octal number. The default value is <code>755</code>.
    - <code>--error-log=file</code>: causes unexpected errors to be logged to
      the file <code>file</code>. Unexpected errors are, for example, failures
      to attach a thread to the Java virtual machine, to load a class, or to
      call a method that is known to exist. Invalid paths, failed permissions
      checks, and other regular filesystem errors are not logged. Java
      exceptions may be written to the log in some or all cases, even when the
      errors they represent would not be considered unexpected, in order to
      permit the developer to see a detailed description of the exception. No
      logging is done by default.

    @param argc Number of command line arguments.
    @param argv A <code>NULL</code>-terminated vector of null-terminated
                strings, one string for each command line argument. The vector
                may be modified by this function.
    @return <code>true</code> if command line arguments are parsed successfully,
            <code>false</code> otherwise.
 */
bool parse_options(int &argc, char **&argv);

/** @brief Fills a <code>fuse_operations</code> structure with
           filesystem-specific function pointers.

    Pointers to functions not implemented are set to <code>NULL</code>.

    @param operations Reference to the structure to be so filled.
 */
void set_operations(fuse_operations& operations);

#endif // #ifndef _FUSE_OPERATIONS_H_
