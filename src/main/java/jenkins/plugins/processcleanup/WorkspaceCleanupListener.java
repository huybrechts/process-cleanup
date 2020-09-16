package jenkins.plugins.processcleanup;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.Build;
import hudson.model.BuildListener;
import hudson.model.Node;
import hudson.model.Project;
import hudson.model.WorkspaceListener;

@Extension
public class WorkspaceCleanupListener extends WorkspaceListener {

    @Override
    public void beforeUse(AbstractBuild b, FilePath workspace, BuildListener listener) {
        if (b instanceof Build) {
            Project p = (Project) b.getProject();
            ProcessCleanupBuildWrapper w = (ProcessCleanupBuildWrapper) p.getBuildWrappersList().get(ProcessCleanupBuildWrapper.class);
            if (w != null) {
                try {
                    Launcher launcher = workspace.createLauncher(listener);

                    if (launcher.isUnix()) return;

                    try {
                        Node builtOn = b.getBuiltOn();
                        FilePath root = builtOn != null ? builtOn.getRootPath() : null;
                        if (root != null) {
                            w.installHandle(root);
                        } else {
                            return;
                        }
                    } catch (Exception e) {
                        e.printStackTrace(listener.error("Could not install handle.exe"));
                        return;
                    }

                    w.killProcesses(b, launcher, listener);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                } catch (Exception e) {
                    e.printStackTrace(listener.error("cleanup failed"));
                }
            }

        }
    }
}
