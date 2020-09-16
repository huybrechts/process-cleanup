package jenkins.plugins.processcleanup;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.console.LineTransformationOutputStream;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.remoting.Callable;
import hudson.tasks.BuildWrapper;
import org.jenkinsci.remoting.RoleChecker;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ProcessCleanupBuildWrapper extends BuildWrapper {

    public static volatile boolean DISABLED = false;

    private String additionalProcesses;
    private String windowsServices;

    @DataBoundConstructor
    public ProcessCleanupBuildWrapper(String additionalProcesses, String windowsServices) {
        this.additionalProcesses = Util.fixEmptyAndTrim(additionalProcesses);
        this.windowsServices = Util.fixEmptyAndTrim(windowsServices);
    }

    public String getAdditionalProcesses() {
        return additionalProcesses;
    }

    public String getWindowsServices() {
        return windowsServices;
    }

    private Environment noop() {
        return new Environment() {
            @Override
            public boolean tearDown(AbstractBuild build, BuildListener listener) throws IOException, InterruptedException {
                return true;
            }
        };
    }

    @Override
    public Environment setUp(AbstractBuild build, final Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
        if (DISABLED || launcher.isUnix()) return noop();

        Node builtOn = build.getBuiltOn();

        if (builtOn == null) {
            return noop();
        }

        final FilePath root = builtOn.getRootPath();

        if (root == null) {
            return noop();
        }

        return new Environment() {
            @Override
            public boolean tearDown(AbstractBuild build, BuildListener listener) throws IOException, InterruptedException {
                try {
                    installHandle(root);
                    killProcesses(build, launcher, listener);
                } catch (IOException e) {
                    listener.getLogger().println(e.getMessage());
                }
                return true;
            }
        };
    }

    void killProcesses(AbstractBuild build, Launcher launcher, final BuildListener listener) throws IOException, InterruptedException {

            final Charset charset = build.getCharset();

            Node builtOn = build.getBuiltOn();
            FilePath root = builtOn != null ? builtOn.getRootPath() : null;
            FilePath workspace = build.getWorkspace();
            if (root != null && workspace != null && launcher.getChannel() != null) {

                if (windowsServices != null) {
                    Set<Pattern> patterns = Arrays.stream(windowsServices.split(","))
                            .map(String::trim)
                            .map(Pattern::compile)
                            .collect(Collectors.toSet());

                    ServicesFilter services = new ServicesFilter(charset, patterns);
                    launcher.launch().cmds("sc", "queryex").stdout(services).join();

                    for (String service: services.names) {
                        // reset failure actions, so it won't restart later
                        launcher.launch().cmds("sc", "failure", service, "actions=", "\"\"", "reset=", "0").join();
                        launcher.launch().cmds("net", "stop", service).stdout(listener).join();
                    }
                }

                Set<String> pids;
                String handleExe = root.child("handle.exe").getRemote();
                try (HandleFilter filter = new HandleFilter(charset, listener)) {
                    launcher.launch().cmds(handleExe, "-accepteula", workspace.getRemote()).stdout(filter).join();
                    pids = new HashSet<>(filter.getPids());
                }
                if (additionalProcesses != null) {
                    Set<String> names = Arrays.stream(additionalProcesses.split(","))
                            .map(String::trim)
                            .map(it -> it.toLowerCase(Locale.ENGLISH))
                            .collect(Collectors.toSet());
                    try (TaskListFilter filter = new TaskListFilter(charset, listener, names)) {
                        launcher.launch().cmds("tasklist").stdout(filter).join();
                        pids.addAll(filter.getPids());
                    }
                }

                try {
                    String localPID = workspace.act(new LocalPID());

                    pids.remove(localPID);

                    if (!pids.isEmpty()) {
                        listener.getLogger().println("[process-cleanup] pids to kill: " + pids + " (I'm " + localPID + ")");

                        for (String pid: pids) {
                            launcher.launch().cmds("wmic", "process", "where", "processid=" + pid, "get", "ProcessID,ParentProcessId,CommandLine").stdout(listener).join();
                        }

                        listener.getLogger().println("[process-cleanup] waiting 5s");
                        Thread.sleep(5000);

                        List<String> args = new ArrayList<String>();
                        args.add("taskkill.exe");
                        args.add("/F");
                        for (String pid: pids) {
                            args.add("/PID");
                            args.add(pid);
                        }

                        launcher.launch().cmds(args).stdout(listener).join();
                    } else {
                        listener.getLogger().println("[process-cleanup] found no processes to kill");
                    }
                } catch (IOException | RuntimeException e) {
                    e.printStackTrace(listener.error("Could not clean up processes"));
                }
            }

    }

    void installHandle(FilePath root) throws IOException, InterruptedException {
        FilePath handle = root.child("handle.exe");
        if (!handle.exists()) {
            handle.copyFrom(getClass().getResource("handle.exe"));
        }
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<BuildWrapper> {

        @Override
        public String getDisplayName() {
            return "Cleanup processes locking the working directory, before checkhout and after the build completes. Windows-only.";
        }
    }

    private static class LocalPID implements Callable<String,RuntimeException> {
        public String call() {
            return ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        }

        @Override
        public void checkRoles(RoleChecker checker) {
        }
    }

    class HandleFilter extends LineTransformationOutputStream {

        private final Pattern PID_PATTERN = Pattern.compile("(\\S+)\\W+pid: (\\d*).*");
        private final Charset charset;
        private final BuildListener listener;
        private Matcher m;

        private Set<String> pids = new HashSet<String>();

        public Set<String> getPids() {
            return pids;
        }

        public HandleFilter(Charset charset, BuildListener listener) {
            this.charset = charset;
            this.listener = listener;
        }

        @Override
        protected void eol(byte[] bytes, int len) throws IOException {
            String line = charset.decode(ByteBuffer.wrap(bytes, 0, len)).toString().trim();
            if (m != null) {
                m.reset(line);
            } else {

                m = PID_PATTERN.matcher(line);
            }
            if (m.matches()) {
                pids.add(m.group(2));
                listener.getLogger().println(line);
            }
        }

    }
    static class TaskListFilter extends LineTransformationOutputStream {

        private final Pattern PID_PATTERN = Pattern.compile("(\\S+)\\W+(\\d*).*");
        private Matcher m;

        private final Charset charset;
        private final BuildListener listener;
        private Set<String> names;
        private Set<String> pids = new HashSet<>();

        public Set<String> getPids() {
            return pids;
        }

        public TaskListFilter(Charset charset, BuildListener listener, Set<String> names) {
            this.charset = charset;
            this.listener = listener;
            this.names = names;
        }

        @Override
        protected void eol(byte[] bytes, int len) throws IOException {
            if (names.isEmpty()) return;

            String line = charset.decode(ByteBuffer.wrap(bytes, 0, len)).toString().trim();
            if (m != null) {
                m.reset(line);
            } else {

                m = PID_PATTERN.matcher(line);
            }
            if (m.matches()) {
                if (names.contains(m.group(1).toLowerCase(Locale.ENGLISH))) {
                    pids.add(m.group(2));
                    listener.getLogger().println(line);
                }
            }
        }

    }

    static class ServicesFilter extends LineTransformationOutputStream {

        private final Pattern SERVICE_PATTERN = Pattern.compile("SERVICE_NAME:\\s+(\\S+)");
        private Matcher m;

        private final Charset charset;
        private final Set<Pattern> patterns;
        private Set<String> names = new HashSet<>();

        public ServicesFilter(Charset charset, Set<Pattern> patterns) {
            this.charset = charset;
            this.patterns = patterns;
        }

        @Override
        protected void eol(byte[] bytes, int len) {
            String line = charset.decode(ByteBuffer.wrap(bytes, 0, len)).toString().trim();
            if (m != null) {
                m.reset(line);
            } else {
                m = SERVICE_PATTERN.matcher(line);
            }
            if (m.matches()) {
                String name = m.group(1);
                if (patterns.stream().anyMatch(it -> it.matcher(name).matches())) {
                    names.add(name);
                }
            }
        }

    }

}
