package apps;

import java.util.*;

import common.*;
import naming.*;

/** Creates empty files.

    <p>
    This application takes a list of remote files as arguments. For each remote
    file that does not exist, the application attempts to create it.
 */
public class Touch extends ClientApplication
{
    /** Application entry point. */
    public static void main(String[] arguments)
    {
        new Touch().run(arguments);
    }

    /** Application main method.

        @param arguments Command line arguments.
     */
    @Override
    public void coreLogic(String[] arguments) throws ApplicationFailure
    {
        // Check that there is exactly one argument.
        if(arguments.length < 1)
            throw new ApplicationFailure("usage: touch file ...");

        // Attempt to create each file that does not exist.
        for(String remote_file : Arrays.asList(arguments))
        {
            try
            {
                touch(remote_file);
                report();
            }
            catch(ApplicationFailure e)
            {
                report(e);
            }
        }
    }

    /** Attempts to create a remote file, if it does not exist.

        <p>
        This method is called for each command line argument.

        @param remote_file Path to the file to be created.
        @throws ApplicationFailure If the file does not yet exist and cannot be
                                   created.
     */
    private void touch(String remote_file) throws ApplicationFailure
    {
        // Parse the argument.
        RemotePath      file;

        try
        {
            file = new RemotePath(remote_file);
        }
        catch(IllegalArgumentException e)
        {
            throw new ApplicationFailure("cannot parse path: " +
                                         e.getMessage());
        }

        // Check that the path given is not the root directory.
        if(file.path.isRoot())
            throw new ApplicationFailure("cannot touch root directory");

        // Lock the parent directory and attempt to make the new file. Then,
        // report the outcome.
        Path            parent = file.path.parent();

        Service         naming_server = NamingStubs.service(file.hostname);

        try
        {
            naming_server.lock(parent, true);
        }
        catch(Throwable t)
        {
            throw new ApplicationFailure("cannot lock " + file.parent() + ": " +
                                         t.getMessage());
        }

        // Create the file, if it does not exist.
        try
        {
            // Ignore the result of createFile. This code relies on the behavior
            // of isDirectory - this is because if the file already exists,
            // createFile will fail, but the touch command does not fail in this
            // case.
            try
            {
                naming_server.createFile(file.path);
            }
            catch(Throwable t) { }

            if(naming_server.isDirectory(file.path))
                throw new ApplicationFailure(file + " is a directory");
        }
        catch(ApplicationFailure e) { throw e; }
        catch(Throwable t)
        {
            throw new ApplicationFailure("cannot access " + file + ": " +
                                         t.getMessage());
        }
        finally
        {
            // Make an effort to unlock the parent directory.
            try
            {
                naming_server.unlock(parent, true);
            }
            catch(Throwable t)
            {
                fatal("could not unlock " + file.parent() + ": " +
                      t.getMessage());
            }
        }
    }
}
