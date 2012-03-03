/** @file main.cpp
    @brief Application entry point.
 */

#include <cstdlib>
#include "common.h"
#include <jni.h>
#include "java.h"
#include <fuse.h>
#include "fuse-operations.h"

/** @brief Application (but not process) entry point.

    This function parses filesystem-specific command line options and then
    starts the FUSE main loop. The function called to start the FUSE main loop
    forks the current process and then terminates it. The child process
    continues to run as a daemon.

    @param argc Number of arguments passed to the program.
    @param argv Arguments passed to the program.
    @return Zero on success, non-zero on failure.
 */
int main(int argc, char **argv)
{
    static fuse_operations      operations;

    // Parse command line arguments.
    if(!parse_options(argc, argv))
        return EXIT_FAILURE;

    // Set FUSE driver function pointers.
    set_operations(operations);

    // Start the FUSE driver.
    return fuse_main(argc, argv, &operations, NULL);
}
