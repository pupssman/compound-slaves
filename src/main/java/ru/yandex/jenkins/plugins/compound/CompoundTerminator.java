package ru.yandex.jenkins.plugins.compound;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Node;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;

import java.io.IOException;

import jenkins.model.Jenkins;

import org.kohsuke.stapler.DataBoundConstructor;

public class CompoundTerminator extends Recorder {

	@DataBoundConstructor
	public CompoundTerminator() {
		// pass
	}

	@Override
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
		Node node = build.getExecutor().getOwner().getNode();

		if (!build.getResult().isBetterOrEqualTo(Result.SUCCESS)) {
			listener.getLogger().println("[compound-terminator] Build has not succeded, leaving slave as-is.");
			return true;
		}

		if (node instanceof CompoundSlave) {
			listener.getLogger().println("[compound-terminator] Found self at node " + node.getDisplayName() + ", which is a CompoundSlave. Terminating...");
			try {
				((CompoundSlave)node)._terminate(listener);
			} finally {
				try {
					Jenkins.getInstance().removeNode(node);
				} catch (IOException e) {
					e.printStackTrace(listener.fatalError("Failed to remove " + node.getDisplayName()));
				}
			}
			listener.getLogger().println("[compound-terminator] Done.");
			return true;
		} else {
			listener.getLogger().println("[compound-terminator] Found self at node " + node.getDisplayName() + " - not a CompoundSlave. Nothing to do.");
			return true;
		}

	}

	@Override
	public boolean needsToRunAfterFinalized() {
		return true;
	}

	@Override
	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.NONE;
	}

	@Extension
	public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {
		@Override
		public boolean isApplicable(Class<? extends AbstractProject> jobType) {
			return true;
		}

		@Override
		public String getDisplayName() {
			return "Terminate the CompoundSlave on success";
		}
	}
}
