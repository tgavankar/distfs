package apps;

import java.util.*;

import common.*;
import naming.*;

/** Creates directories.

    <p>
    This application takes a list of remote directories as arguments, and
    attempts to create each one.
 */
public class MakeDirectory extends ClientApplication
{
    /** Application entry point. */
    public static void main(String[] arguments)
    {
        new MakeDirectory().run(arguments);
    }

    /** Application main method.

        @param arguments Command line arguments.
     */
    @Override
    public void coreLogic(String[] arguments) throws ApplicationFailure
    {
        // Check that there is exactly one argument.
        if(arguments.length < 1)
            throw new ApplicationFailure("usage: mkdir directory ...");

        // Go through all arguments and attempt to make each directory.
        for(String remote_directory : Arrays.asList(arguments))
        {
            try
            {
                make(remote_directory);
                report();
            }
            catch(ApplicationFailure e)
            {
                report(e);
            }
        }
    }

    /** Attempts to create the given remote directory.

        <p>
        This method is called for each command line argument.

        @param remote_directory Remote path to the directory that is to be
                                created.
        @throws ApplicationFailure If the directory cannot be created.
     */
    private void make(String remote_directory) throws ApplicationFailure
    {
        // Parse the argument.
        RemotePath      directory;

        try
        {
            directory = new RemotePath(remote_directory);
        }
        catch(IllegalArgumentException e)
        {
            throw new ApplicationFailure("cannot parse path: " +
                                         e.getMessage());
        }

        // Check that the remote path is not the root directory.
        if(directory.path.isRoot())
            throw new ApplicationFailure("cannot create root directory");

        // Lock the parent directory and attempt to make the new directory on
        // the given naming server.
        Path            parent = directory.path.parent();

        Service         naming_server = NamingStubs.service(directory.hostname);

        try
        {
            naming_server.lock(parent, true);
        }
        catch(Throwable t)
        {
            throw new ApplicationFailure("cannot lock " + directory.parent() +
                                         ": " + t.getMessage());
        }

        // Create the new directory.
        try
        {
            if(!naming_server.createDirectory(directory.path))
            {
                throw new ApplicationFailure("cannot create directory " +
                                             directory);
            }
        }
        catch(ApplicationFailure e) { throw e; }
        catch(Throwable t)
        {
            throw new ApplicationFailure("cannot create directory " +
                                         directory + ": " + t.getMessage());
        }
        finally
        {
            // In all cases, make an effort to unlock the parent directory.
            try
            {
                naming_server.unlock(parent, true);
            }
            catch(Throwable t)
            {
                fatal("could not unlock " + directory.parent() + ": " +
                      t.getMessage());
            }
        }
    }
}
