package ru.yandex.jenkins.plugins.compound;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.BuildListener;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.model.WorkspaceListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.Slave;
import hudson.slaves.WorkspaceList;
import hudson.slaves.WorkspaceList.Lease;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ListBoxModel;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jedi.functional.FunctionalPrimitives;
import jenkins.model.Jenkins;

import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Builder to run at sub-nodes of a {@link CompoundSlave}
 * @author pupssman
 *
 */
public class CompoundBuilder extends Builder {
	/**
	 * The role of sub-slave to run upon
	 */
	private final String role;
	private final Builder actualBuilder;
	private final int number;

	@DataBoundConstructor
	public CompoundBuilder(String role, String number, Builder actualBuilder) {
		this.role = role;
		this.number = Integer.parseInt(number);
		this.actualBuilder = actualBuilder;
	}

	/**
	 * Performs WS initialization on subslave
	 * @return workspace location
	 *
	 * @param build
	 * @param launcher
	 * @param listener
	 * @param slave
	 * @throws IOException
	 * @throws InterruptedException
	 */
	private String prepareEnvironment(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener, Slave slave) throws IOException, InterruptedException {
		WorkspaceList workspaceList = slave.getComputer().getWorkspaceList();
		Lease workspaceLease = workspaceList.allocate(slave.getWorkspaceFor((TopLevelItem) (build.getProject())), build);

		String workspace = workspaceLease.path.getRemote();
		log(listener, "Provisioning workspace " + workspace + " on " + slave.getDisplayName());

		workspaceLease.path.mkdirs();
		slave.getFileSystemProvisioner().prepareWorkspace(build, workspaceLease.path,listener);

		for (WorkspaceListener wl : WorkspaceListener.all()) {
			wl.beforeUse(build, workspaceLease.path, listener);
		}

		return workspace;
	}

	private void log(BuildListener listener, String message) {
		listener.getLogger().println("[CompoundBuilder] " + message);
	}

	@SuppressWarnings("deprecation")
	@Override
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
		try {

			Node runningNode = Executor.currentExecutor().getOwner().getNode();

			log(listener, "Found myself at node " + runningNode.getDisplayName() + ", have role " + role);

			if (runningNode instanceof CompoundSlave) {
				log(listener, "This node is a CompondSlave, wooho!");

				CompoundSlave compoundSlave = (CompoundSlave) runningNode;

				log(listener, "It has slaves with roles:" + FunctionalPrimitives.join(compoundSlave.getAllSlaves().keySet(), ", "));

				List<Slave> slaves = compoundSlave.getSlaves(role);

				Launcher actualLauncher;
				AbstractBuild<?, ?> actualBuild;

				boolean result = true;

				if (!slaves.isEmpty() && !role.equals("ROOT")) {
					int slaveNumber = 0;

					for (Slave slave: slaves) {
						slaveNumber ++;

						// we run for slave with given number or any slave if number is 0
						if (slaveNumber != number && number != 0) {
							log(listener, "This slave " + slave.getDisplayName() + " is number "+ slaveNumber + ", but we seek " + number);
							continue;
						}

						log(listener, "Got a separate slave " + slave.getDisplayName() + " for role " + role + " and number " + number);
						log(listener, "Preparing workspace on slave " + slave.getDisplayName());
						String workspace = prepareEnvironment(build, launcher, listener, slave);

						log(listener, "Running actual sub-builder.");
						actualLauncher = new Launcher.RemoteLauncher(listener, slave.getChannel(), slave.getComputer().isUnix());
						Map<String, String> envOverrides = new HashMap<String, String>();
						envOverrides.put("WORKSPACE", workspace);
						actualBuild = new PatchedBuild(build, workspace, slave, envOverrides);

						result &= actualBuilder.perform(actualBuild, actualLauncher, listener);
					}
				} else {
					log(listener, "No separate slave, running on a master.");
					actualLauncher = launcher;
					actualBuild = build;

					result = actualBuilder.perform(actualBuild, actualLauncher, listener);
				}

				return result;
			} else {
				log(listener, "Not a compound node. Running as-is.");
				return actualBuilder.perform(build, launcher, listener);
			}

		} catch (IOException e) {
			Util.displayIOException(e,listener);
			return false;
		}
	}

	public static class PatchedBuild<X extends AbstractProject<X,Y>, Y extends AbstractBuild<X, Y>> extends AbstractBuild<X, Y> {
		private final Map<String, String> envOverrides;
		private final AbstractBuild<X, Y> actualBuild;
		private final Slave builtSlave;
		public PatchedBuild(AbstractBuild<X,Y> actualBuild, String workspace, Slave builtSlave, Map<String, String> envOverrides) throws IOException {
			super(actualBuild.getProject());
			this.actualBuild = actualBuild;
			setWorkspace(builtSlave.createPath(workspace));
			this.builtSlave = builtSlave;
			this.envOverrides = envOverrides;
		}
		@Override
		public void run() {
			actualBuild.run();
		}

		@Override
		public EnvVars getEnvironment(TaskListener log) throws IOException, InterruptedException {
			EnvVars superVars = super.getEnvironment(log);

			for (String key: envOverrides.keySet()) {
				superVars.put(key, envOverrides.get(key));
			}

			return superVars;
		}

		@Override
		public Map<String,String> getBuildVariables() {
			return actualBuild.getBuildVariables();
		}

		@Override
		public Executor getExecutor() {
			return actualBuild.getExecutor();
		}

		@Override
		public Node getBuiltOn() {
			return builtSlave;
		}

	}

	public DescriptorImpl getDescriptor() {
		return (DescriptorImpl)super.getDescriptor();
	}

	@Extension
	public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

		@SuppressWarnings("rawtypes")
		@Override
		public boolean isApplicable(Class<? extends AbstractProject> jobType) {
			return true;	//FIXME: should note if we integrate that with a special kind of job
		}

		public ListBoxModel doFillRoleItems() {
			ListBoxModel model = new ListBoxModel();

			for (String role: ((ru.yandex.jenkins.plugins.compound.CompoundSlave.DescriptorImpl) Jenkins.getInstance().getDescriptor(CompoundSlave.class)).getRoles()) {
				model.add(role, role);
			}

			return model;
		}

		public ListBoxModel doFillNumberItems() {
			ListBoxModel model = new ListBoxModel();

			model.add("Everyone", "0");
			for (int i = 1; i < 10 ; i ++) {
				model.add(Integer.toString(i));
			}

			return model;
		}

		@Override
		public String getDisplayName() {
			return "Run something on a sub-node";
		}
	}

	public Builder getActualBuilder() {
		return actualBuilder;
	}

	public String getRole() {
		return role;
	}

	public Integer getNumber() {
		return number;
	}
}
