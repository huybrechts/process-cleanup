package jenkins.plugins.processcleanup;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jenkinsci.remoting.RoleChecker;
import org.kohsuke.stapler.DataBoundConstructor;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.console.LineTransformationOutputStream;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.remoting.Callable;
import hudson.tasks.BuildWrapper;

public class ProcessCleanupBuildWrapper extends BuildWrapper {

    public static volatile boolean DISABLED = false;

    private transient final Environment NOOP = new Environment() {
        
        @Override
        public boolean tearDown(
                @SuppressWarnings("rawtypes") final AbstractBuild build,
                BuildListener listener) throws IOException, InterruptedException {
            
            return true;
        }
    };

    @DataBoundConstructor
    public ProcessCleanupBuildWrapper() {}

    @Override
    public Environment setUp(
            @SuppressWarnings("rawtypes") final AbstractBuild build,
            final Launcher launcher,
            final BuildListener listener) throws IOException, InterruptedException {
        
        if (DISABLED) {
            return NOOP;
        }
        
        if (build == null) {
            // This really should not happen, but FindBugs is complaining about it.
            throw new IllegalArgumentException("Given build is null.");
        }
        
        final Node node = build.getBuiltOn();
        
        if (node == null) {
            throw new IllegalStateException("Slave that this build run on no longer exists.");
        }
        
        final Computer computer = node.toComputer();
        
        if (computer == null) {
            throw new IllegalStateException("Node '" + node.getNodeName() + "' does not have executors.");
        }
        
        final Boolean isUnix = computer.isUnix();
        
        if (isUnix == null) {
            throw new IllegalStateException("Computer '" + computer.getName() + "' is disconnected.");
        }
        
        if (isUnix) {
            return NOOP;
        }

        try {
            installHandle(node);
        } catch (IOException e) {
            e.printStackTrace(listener.error("Could not install handle.exe"));
            return NOOP;
        }

        killProcesses(build, launcher, listener);

        return new Environment() {
            
            @Override
            public boolean tearDown(
                    @SuppressWarnings("rawtypes") final AbstractBuild build,
                    final BuildListener listener) throws IOException, InterruptedException {
                
                killProcesses(build, launcher, listener);
                return true;
            }
        };
    }

    private void killProcesses(
            @SuppressWarnings("rawtypes") final AbstractBuild build,
            final Launcher launcher,
            final BuildListener listener) throws IOException, InterruptedException {

        final Node node = build.getBuiltOn();
        
        if (node == null) {
            throw new IllegalStateException("Slave that this build run on no longer exists.");
        }
        
        try {
            final Charset charset = build.getCharset();

            class PidFilter extends LineTransformationOutputStream {

                private Pattern PID_PATTERN = Pattern.compile(".*pid: (\\d*).*");

                private Set<String> pids = new HashSet<String>();

                public Set<String> getPids() {
                    return pids;
                }

                @Override
                protected void eol(byte[] bytes, int len) throws IOException {
                    String line = charset.decode(ByteBuffer.wrap(bytes, 0, len)).toString().trim();
                    Matcher m = PID_PATTERN.matcher(line);
                    if (m.matches()) {
                        pids.add(m.group(1));
                        listener.getLogger().println(line);
                    }
                }

            }

            PidFilter filter = new PidFilter();

            String handleExe = node.getRootPath().child("handle.exe").getRemote();
            launcher.launch().cmds(handleExe, "-accepteula", build.getWorkspace().getRemote()).stdout(filter).join();

            String localPID = build.getWorkspace().act(new LocalPID());

            Set<String> pids = filter.getPids();
            pids.remove(localPID);

            if (!pids.isEmpty()) {
                listener.getLogger().println("[process-cleanup] pids to kill: " + pids);

                List<String> args = new ArrayList<String>();
                args.add("taskkill.exe");
                args.add("/F");
                args.add("/T");
                for (String pid: pids) {
                    args.add("/PID");
                    args.add(pid);
                }

                launcher.launch().cmds(args).stdout(listener).join();
            }
        } catch (IOException e) {
            e.printStackTrace(listener.error("Could not clean up processes"));
        } catch (RuntimeException e) {
            e.printStackTrace(listener.error("Could not clean up processes"));
        }

    }

    private void installHandle(Node node) throws IOException, InterruptedException {
        FilePath handle = node.getRootPath().child("handle.exe");
        if (!handle.exists()) {
            handle.copyFrom(getClass().getResource("handle.exe"));
        }
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<BuildWrapper> {

        @Override
        public String getDisplayName() {
            return "Cleanup processes locking the working directory before and after the build";
        }
    }

    private static final class LocalPID implements Callable<String, RuntimeException> {
        
        private static final long serialVersionUID = -1472189547109950350L;

        public String call() throws RuntimeException {
            
            return ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        }

        @Override
        public void checkRoles(RoleChecker checker) throws SecurityException {}
    }
}
