package ru.yandex.jenkins.plugins.compound;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Hudson;
import hudson.model.InvisibleAction;
import hudson.model.TaskListener;
import hudson.model.EnvironmentContributor;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.Slave;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;

/**
 * This thing is used to contribute slave parameters to running build
 *
 * @author pupssman
 */
@Extension
public class CompoundEnvironmentContributor extends EnvironmentContributor {

	public static final class EnvironmentAction extends InvisibleAction {
		private final Map<String, String> values;

		public EnvironmentAction(Map<String, String> values) {
			this.values = values;
		}

		public Map<String, String> getValues() {
			return values;
		}
	}

	@Override
	public void buildEnvironmentFor(@SuppressWarnings("rawtypes") Run run, EnvVars envs, TaskListener listener) throws IOException, InterruptedException {
		Node node = run.getExecutor().getOwner().getNode();

		if (node instanceof CompoundSlave) {
			listener.getLogger().println("[compound-slave] contibuting environment for " + run.getFullDisplayName());

			buildEnvironmentFor((CompoundSlave) node, envs, listener);
		}

	}

	private void buildEnvironmentFor(CompoundSlave slave, EnvVars envs, TaskListener listener) {
		EnvironmentAction environmentAction = slave.toComputer().getAction(EnvironmentAction.class);

		Map<String, String> values;

		if (environmentAction != null) {
			values = environmentAction.getValues();
		} else {
			listener.getLogger().println("[compound-slave] no environment known - computing...");
			values = computeValues(slave, listener);
			slave.toComputer().addAction(new EnvironmentAction(values));
		}

		envs.putAll(values);
	}

	/**
	 * Compute actual values based on given {@link CompoundSlave}
	 *
	 * So far this does nothing (upstream implementation depends on a closed plugin)
	 * @param slave
	 * @param listener
	 * @return
	 */
	private Map<String, String> computeValues(CompoundSlave slave, TaskListener listener) {
		Map<String, String> values;
		values = new HashMap<String, String>();

		return values;
	}

}
