package qupath.ext.qust;

import org.controlsfx.tools.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A wrapper to run python virtualenvs, that tries to figure out the commands to run based on the environment type
 *
 * @author Olivier Burri
 * @author Romain Guiet
 * @author Nicolas Chiaruttini
 * 
 * @author huangch
 */
public class VirtualEnvironmentRunner {
    private final static Logger logger = LoggerFactory.getLogger(VirtualEnvironmentRunner.class);
    private final EnvType envType;
    private String name;
    private String environmentNameOrPath;

    private List<String> arguments;

    /**
     * This enum helps us figure out the type of virtualenv. We need to change {@link #getActivationCommand()} as well.
     */
    public enum EnvType {
        CONDA("Anaconda or Miniconda", "If you need to start your virtual environment with 'conda activate' then this is the type for you"),
        VENV( "Python venv", "If you use 'myenv/Scripts/activate' to call your virtual environment, then use this environment type"),
        EXE("Python Executable", "Use this if you'd like to call the python executable directly. Can be useful in case you have issues with conda."),
        OTHER("Other (Unsupported)", "Currently only conda and venv are supported.");

        private final String description;
        @SuppressWarnings("unused")
		private final String help;

        EnvType(String description, String help) {
            this.description = description;
            this.help = help;
        }

        public String getDescription() {return this.description;}

        @Override
        public String toString() {
            return this.description;
        }
    }

    public VirtualEnvironmentRunner(String environmentNameOrPath, EnvType type, String name) {
        this.environmentNameOrPath = environmentNameOrPath;
        this.envType = type;
        this.name = name;
        if (envType.equals(EnvType.OTHER))
            logger.error("Environment is unknown, please set the environment type to something different than 'Other'");
    }

    /**
     * This methods returns the command that will be needed by the {@link ProcessBuilder}, to start Python in the
     * desired virtual environment type.
     * Issue is that under windows you can just pile a bunch of Strings together, and it runs
     * In Mac or UNIX, the bash -c command must be followed by the full command enclosed in quotes
     * @return a list of Strings up to the start of the 'python' command. Use {@link #setArguments(List)} to set the actual command to run.
     */
    private List<String> getActivationCommand() {

        Platform platform = Platform.getCurrent();
        List<String> cmd = new ArrayList<>();

        switch (envType) {
            case CONDA:
                switch (platform) {
                    case WINDOWS:
                        cmd.addAll(Arrays.asList("CALL", "conda.bat", "activate", environmentNameOrPath, "&", "python"));
                        break;
                    case UNIX:
                    case OSX: 	
                        cmd.addAll(Arrays.asList("conda", "activate", environmentNameOrPath, ";", "python"));
                        break;
                    default:
    					return null;
                }
                break;
            case VENV:
                switch (platform) {
                    case WINDOWS:
                        cmd.addAll(Arrays.asList(new File(environmentNameOrPath, "Scripts/python").getAbsolutePath()));
                        break;
                    case UNIX:
                    case OSX:
                        cmd.addAll(Arrays.asList(new File(environmentNameOrPath, "bin/python").getAbsolutePath()));
                        break;
				default:
					return null;
                }
                break;
            case EXE:
                cmd.add(environmentNameOrPath);
                break;
            case OTHER:
                return null;
        }
        return cmd;
    }

    /**
     * This is the code you actually want to run after 'python'. For example adding {@code Arrays.asList("--version")}
     * should return the version of python that is being run.
     * @param arguments
     */
    public void setArguments(List<String> arguments) {
        this.arguments = arguments;
    }

    /**
     * This builds, runs the command and outputs it to the logger as it is being run
     * @throws IOException // In case there is an issue starting the process
     * @throws InterruptedException // In case there is an issue after the process is started
     * @return a string list containing the log of the command
     */
    public String[] runCommand() throws IOException, InterruptedException {

        List<String> logResults = new ArrayList<>();

        // Get how to start the command, based on the VENV Type
        List<String> command = getActivationCommand();

        // Get the arguments specific to the command we want to run
        command.addAll(arguments);

        // OK so here we need to either just continue appending the commands in the case of windows
        // or making a big string for NIX systems
        List<String> shell = new ArrayList<>();

        switch (Platform.getCurrent()) {

            case UNIX:
            case OSX:
                shell.addAll(Arrays.asList("bash", "-c"));

                // If there are spaces, then we should encapsulate the command with quotes
                command = command.stream()
//                		.map(s -> {
//                            if (s.trim().contains(" "))
//                                return "\"" + s.trim() + "\"";
//                            return s;
//                        })
                		.collect(Collectors.toList());

                // The last part needs to be sent as a single string, otherwise it does not run
//                String cmdString = command.toString().replace(",","");
                String cmdString = String.join(" ", command);
                shell.add(cmdString);
                break;

            case WINDOWS:
            default:
                shell.addAll(Arrays.asList("cmd.exe", "/C"));
                shell.addAll(command);
                break;
        }


        // Try to make a command that is fully readable and that can be copy pasted
        List<String> printable = shell.stream()
//        		.map(s -> {
//		            // add quotes if there are spaces in the paths
//		            if (s.contains(" "))
//		                return "\"" + s + "\"";
//		            else
//		                return s;
//		        })
        		.collect(Collectors.toList());
//        String executionString = printable.toString().replace(",", "");
        String executionString = String.join(" ", printable);

        logger.info("Executing command:\n{}", executionString);
        // logger.info("This command should run directly if copy-pasted into your shell");

        // Now the cmd line is ready
        ProcessBuilder pb = new ProcessBuilder(shell).redirectErrorStream(true);
        
        // Configure processbuilder env for GPUs
        pb.environment().put("CUDA_VISIBLE_DEVICES", System.getenv("CUDA_VISIBLE_DEVICES")==null? "0": System.getenv("CUDA_VISIBLE_DEVICES"));
        pb.environment().put("NVIDIA_VISIBLE_DEVICES", System.getenv("NVIDIA_VISIBLE_DEVICES")==null? "all": System.getenv("NVIDIA_VISIBLE_DEVICES"));
        
        // Start the process and follow it throughout
        Process p = pb.start();

        Thread t = new Thread(Thread.currentThread().getName() + "-" + p.hashCode()) {
            @Override
            public void run() {
                BufferedReader stdIn = new BufferedReader(new InputStreamReader(p.getInputStream()));
                try {
                    for (String line = stdIn.readLine(); line != null; ) {
                        logger.info("{}: {}", name, line);
                        logResults.add(line);
                        line = stdIn.readLine();
                    }
                } catch (IOException e) {
                    logger.warn(e.getMessage());
                }
            }
        };
        t.setDaemon(true);
        t.start();

        p.waitFor();

        logger.info("Virtual Environment Runner Finished");

        int exitValue = p.exitValue();

        if (exitValue != 0) {
            logger.error("Runner '{}' exited with value {}. Please check output above for indications of the problem.", name, exitValue);
        }

        // Return the log in case we want to use it
        return logResults.toArray(new String[0]);
    }
}